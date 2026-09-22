#include "movement_checks.hpp"
#include <algorithm>
#include <cmath>
#include <stdexcept>

namespace ac {
    namespace {
        bool range(double x, double lo, double hi) {
            return std::isfinite(x) && x >= lo && x <= hi;
        }
        bool position(const Vector3 &p) {
            return range(p.x, -3e7, 3e7) && range(p.y, -2048, 2048) && range(p.z, -3e7, 3e7);
        }
        double horizontal(const Vector3 &p) {
            return std::hypot(p.x, p.z);
        }
        Vector3 subtract(const Vector3 &a, const Vector3 &b) {
            return {a.x - b.x, a.y - b.y, a.z - b.z};
        }
        bool usable(const MovementEvent &e, const CheckContext &ctx, double maxQueue) {
            double age = combat_elapsed_ms(e.sampled_ns, ctx.event.observed_ns);
            return combat_model(ctx.player.identity) && e.context.available &&
                   !e.context.world_uuid.empty() && age >= 0 && age <= maxQueue;
        }
    } // namespace

    TimerCheck::TimerCheck(TimerSettings s) : settings_(s) {
        if (!range(s.credit_ms, 100, 2000) || !range(s.lead_ms, 100, 2000) ||
            !range(s.sustain_ms, 250, 3000) || !range(s.warmup_ms, 500, 10000) ||
            !range(s.maximum_queue_ms, 1, 250) || !range(s.uncertain_gap_ms, 500, 3000))
            throw std::invalid_argument("Invalid Timer settings");
    }
    void TimerCheck::reset(std::string_view) {
        last_.reset();
        started_.reset();
        ready_.reset();
        over_.reset();
        packet_ = count_ = consecutive_ = 0;
        balance_ = 0;
    }
    void TimerCheck::registerHandlers(CheckManager &m) {
        m.on<MovementEvent>([this](const auto &e, auto &c) { movement(e, c); });
        m.on<TeleportEvent>([this](const auto &, auto &) { reset("teleport"); });
        m.on<CorrectionEvent>([this](const auto &, auto &) { reset("correction"); });
    }
    void TimerCheck::movement(const MovementEvent &e, CheckContext &c) {
        if (!usable(e, c, settings_.maximum_queue_ms) || e.packet_sequence <= packet_) {
            reset("unreliable");
            return;
        }
        packet_ = e.packet_sequence;
        auto now = c.event.observed_ns;
        double elapsed = last_ ? combat_elapsed_ms(now, *last_) : 0;
        if (!last_ || elapsed < 0 || elapsed >= settings_.uncertain_gap_ms) {
            last_ = now;
            ready_ = now + static_cast<std::uint64_t>(settings_.warmup_ms * 1e6);
            started_.reset();
            over_.reset();
            count_ = consecutive_ = 0;
            balance_ = -settings_.credit_ms;
            return;
        }
        last_ = now;
        if (now < *ready_)
            return;
        if (!started_) {
            started_ = now;
            elapsed = 0;
        }
        // Charge every Flying variant, including look-only and on-ground-only packets.
        balance_ = std::min(5000.0, std::max(-settings_.credit_ms, balance_ - elapsed) + 50.0);
        ++count_;
        if (balance_ <= settings_.lead_ms) {
            over_.reset();
            consecutive_ = 0;
            return;
        }
        if (!over_)
            over_ = now;
        ++consecutive_;
        if (count_ < 40 || combat_elapsed_ms(now, *started_) < 2000 || consecutive_ < 8 ||
            combat_elapsed_ms(now, *over_) < settings_.sustain_ms)
            return;
        c.emit("suspicious", std::string(id()), "sustained_excess_client_tick_budget",
               {{"source", "a/yG.f(rB) -> Timer.timerSpeed"},
                {"lead_ms", combat_number(balance_)},
                {"counted_packets", std::to_string(count_)},
                {"elapsed_ms", combat_number(combat_elapsed_ms(now, *started_))},
                {"tick_cost_ms", "50"},
                {"model", "eligible_1_8_walking_cadence"},
                {"validation", "mechanism_and_synthetic_tests;live_calibration_pending"}});
        balance_ = 0;
        over_.reset();
        consecutive_ = 0;
    }

    MovementCheck::MovementCheck(MovementKind k, MovementSettings s) : kind_(k), settings_(s) {
        if (!range(s.horizontal_epsilon, .001, .2) || !range(s.vertical_epsilon, .001, .2) ||
            !range(s.maximum_queue_ms, 1, 250) || s.alert_after < 2 || s.alert_after > 100)
            throw std::invalid_argument("Invalid movement settings");
    }
    std::string_view MovementCheck::id() const noexcept {
        switch (kind_) {
        case MovementKind::speed:
            return "speed.plain.v1";
        case MovementKind::fly:
            return "fly.airborne.v1";
        case MovementKind::noslow:
            return "noslow.item_input.v1";
        case MovementKind::nofall:
            return "nofall.ground_claim.v1";
        case MovementKind::keepsprint:
            return "keepsprint.attack_retention.v1";
        }
        return "movement.invalid";
    }
    void MovementCheck::reset(std::string_view) {
        previous_.reset();
        delta_.reset();
        samples_.clear();
        attack_.reset();
        last_ns_ = last_packet_ = grace_until_ = last_batch_ = 0;
    }
    void MovementCheck::registerHandlers(CheckManager &m) {
        m.on<MovementEvent>([this](const auto &e, auto &c) { movement(e, c); });
        auto invalidate = [this](const auto &, auto &c) {
            reset("boundary");
            grace_until_ = c.event.observed_ns + 2000000000ULL;
        };
        m.on<TeleportEvent>(invalidate);
        m.on<CorrectionEvent>(invalidate);
        m.on<ImpulseEvent>(invalidate);
        m.on<AttackEvent>([this](const AttackEvent &e, CheckContext &c) {
            if (kind_ == MovementKind::keepsprint && e.context.available && e.context.target_player &&
                e.context.attacker_sprinting && e.context.ping_ms >= 0 && e.context.ping_ms <= 75 &&
                combat_elapsed_ms(e.sampled_ns, c.event.observed_ns) >= 0 &&
                combat_elapsed_ms(e.sampled_ns, c.event.observed_ns) <= settings_.maximum_queue_ms)
                attack_ = c.event.observed_ns;
        });
    }
    void MovementCheck::movement(const MovementEvent &e, CheckContext &c) {
        const auto now = c.event.observed_ns;
        samples_.expire(now, 10000);
        if (!usable(e, c, settings_.maximum_queue_ms) || !e.has_position || !position(e.position) ||
            e.packet_sequence <= last_packet_ || now < grace_until_) {
            previous_.reset();
            delta_.reset();
            samples_.clear();
            return;
        }
        last_packet_ = e.packet_sequence;
        if (!previous_) {
            previous_ = e;
            last_ns_ = now;
            return;
        }
        MovementEvent before = *previous_;
        previous_ = e;
        double elapsed = combat_elapsed_ms(now, last_ns_);
        last_ns_ = now;
        if (elapsed < 0 || elapsed > 150 || before.context.world_uuid != e.context.world_uuid) {
            delta_.reset();
            samples_.clear();
            return;
        }
        Vector3 move = subtract(e.position, before.position);
        auto old = delta_;
        delta_ = move;
        if (!old || !e.context.clear_path || !before.context.clear_path || horizontal(move) > 3 ||
            std::abs(move.y) > 3) {
            samples_.clear();
            return;
        }
        if (e.context.movement_speed != before.context.movement_speed ||
            e.context.friction != before.context.friction) {
            samples_.clear();
            return;
        }
        if (e.read_batch <= last_batch_)
            return;
        last_batch_ = e.read_batch;
        bool suspect = false, eligible = false;
        double measured = 0, bound = 0;
        const char *source = "", *model = "";
        const bool flat = e.context.flat_ground && before.context.flat_ground;
        const bool air = !e.context.source_supported && !e.context.destination_supported &&
                         !before.context.source_supported && !before.context.destination_supported;
        if (kind_ == MovementKind::nofall) {
            eligible = air && move.y < -.03 && old->y < -.03;
            suspect = eligible && e.on_ground;
            measured = move.y;
            source = "a/ys.H(W9): Wk.setOnGround(true)";
            model = "descending_clear_air_no_support";
        } else if (kind_ == MovementKind::fly) {
            eligible = air;
            bound = (old->y - .08) * static_cast<double>(.98f);
            measured = move.y;
            suspect = eligible && std::abs(measured - bound) > settings_.vertical_epsilon;
            source = "a/yp: movement Y override";
            model = "collision_free_air_gravity_drag";
        } else {
            if (!range(e.context.movement_speed, 0, .5) || !range(e.context.friction, .5, 1))
                return;
            double friction =
                flat ? static_cast<double>(.91f) * e.context.friction : static_cast<double>(.91f);
            // Upper envelope permits any horizontal input direction and either sprint state.
            double acceleration =
                flat ? e.context.movement_speed * 1.3 * .16277136 / (friction * friction * friction) : .026;
            double retention = 1;
            if (kind_ == MovementKind::speed) {
                eligible = flat || air;
                source = "a/yq,a/z5: staged horizontal override";
                model = "plain_motion_input_envelope";
            }
            if (kind_ == MovementKind::noslow) {
                eligible = flat && e.context.using_item && before.context.using_item;
                acceleration *= std::sqrt(2.0) * .2; // Conservative diagonal input; omits .98 damping.
                source = "a/yl.z(W9): restore +/-0.2 input to +/-1";
                model = "flat_ground_continuous_item_use";
            }
            if (kind_ == MovementKind::keepsprint) {
                eligible = flat && attack_ && now >= *attack_ && combat_elapsed_ms(now, *attack_) <= 150 &&
                           before.context.sprinting && e.context.sprinting && !e.context.using_item;
                // This opt-in hypothesis requires a stable, already-sprinting attribute.
                acceleration /= 1.3;
                retention = .6;
                source = "a/y7.F(WL): velocity / 0.6 * retainFactor";
                model = "conditional_sprint_attack_slowdown";
                attack_.reset();
            }
            measured =
                std::hypot(move.x - old->x * friction * retention, move.z - old->z * friction * retention);
            bound = acceleration;
            suspect = eligible && measured > bound + settings_.horizontal_epsilon;
        }
        if (!eligible)
            return;
        if (!suspect) {
            samples_.clear();
            return;
        }
        if (samples_.add(c.event, 10000) < settings_.alert_after)
            return;
        Evidence evidence{{"source", source},
                          {"model", model},
                          {"measured", combat_number(measured)},
                          {"expected_or_maximum", combat_number(bound)},
                          {"samples", std::to_string(samples_.count)},
                          {"horizontal_epsilon", combat_number(settings_.horizontal_epsilon)},
                          {"vertical_epsilon", combat_number(settings_.vertical_epsilon)},
                          {"dx", combat_number(move.x)},
                          {"dy", combat_number(move.y)},
                          {"dz", combat_number(move.z)},
                          {"previous_dx", combat_number(old->x)},
                          {"previous_dy", combat_number(old->y)},
                          {"previous_dz", combat_number(old->z)},
                          {"movement_speed", combat_number(e.context.movement_speed)},
                          {"friction", combat_number(e.context.friction)},
                          {"packet_ground", e.on_ground ? "true" : "false"},
                          {"using_item", e.context.using_item ? "true" : "false"},
                          {"first_sample_event", std::to_string(samples_.first_event)},
                          {"validation", "bounded_model_and_synthetic_tests;live_calibration_pending"}};
        if (kind_ == MovementKind::keepsprint)
            evidence.emplace_back("client_attack_branch",
                                  "ASSUMED_FROM_SPRINT_PLAYER_ATTACK;NOT_ACKNOWLEDGED");
        c.emit("suspicious", std::string(id()), "repeated_movement_model_contradiction", std::move(evidence));
        samples_.clear();
    }
} // namespace ac
