#include "velocity.hpp"
#include <algorithm>
#include <cmath>
#include <stdexcept>

namespace ac {
    VelocityCheck::VelocityCheck(VelocitySettings s) : settings_(s) {
        if (!std::isfinite(s.epsilon) || s.epsilon < .005 || s.epsilon > .2 ||
            !std::isfinite(s.maximum_queue_ms) || s.maximum_queue_ms < 1 || s.maximum_queue_ms > 250 ||
            s.alert_after < 2 || s.alert_after > 100)
            throw std::invalid_argument("Invalid Velocity settings");
    }
    void VelocityCheck::clear_impulse() {
        pending_.reset();
        candidates_.clear();
        acknowledged_ = false;
        after_ack_ = movements_ = 0;
    }
    void VelocityCheck::reset(std::string_view) {
        clear_impulse();
        previous_.reset();
        last_delta_ = {};
        samples_.clear();
        last_packet_ = last_time_ = 0;
    }
    void VelocityCheck::registerHandlers(CheckManager &m) {
        m.on<MovementEvent>([this](const auto &e, auto &c) { movement(e, c); });
        m.on<ImpulseEvent>([this](const ImpulseEvent &e, CheckContext &c) {
            if (pending_) {
                clear_impulse();
                samples_.clear();
                return;
            } // overlapping impulses are not modeled
            if (!previous_ || !e.token || !std::isfinite(e.velocity.x) || !std::isfinite(e.velocity.y) ||
                !std::isfinite(e.velocity.z) || std::hypot(e.velocity.x, e.velocity.z) > 4 ||
                std::abs(e.velocity.y) > 4 ||
                (std::hypot(e.velocity.x, e.velocity.z) < .1 && std::abs(e.velocity.y) < .1))
                return;
            pending_ = e;
            sent_ = c.event.observed_ns;
            acknowledged_ = false;
            after_ack_ = movements_ = 0;
            candidates_.clear();
        });
        m.on<ImpulseAckEvent>([this](const ImpulseAckEvent &e, CheckContext &c) {
            if (pending_ && pending_->token == e.token && c.event.observed_ns >= sent_ &&
                combat_elapsed_ms(c.event.observed_ns, sent_) <= 2000)
                acknowledged_ = true;
        });
        auto boundary = [this](const auto &, auto &) { reset("boundary"); };
        m.on<TeleportEvent>(boundary);
        m.on<CorrectionEvent>(boundary);
        // Local sprint-attack slowdown is not acknowledged by the client.
        m.on<AttackEvent>(boundary);
    }
    void VelocityCheck::movement(const MovementEvent &e, CheckContext &c) {
        double queue = combat_elapsed_ms(e.sampled_ns, c.event.observed_ns);
        if (!combat_model(c.player.identity) || !e.context.available || !e.has_position ||
            !e.context.clear_path || e.packet_sequence <= last_packet_ || queue < 0 ||
            queue > settings_.maximum_queue_ms || !std::isfinite(e.position.x) ||
            !std::isfinite(e.position.y) || !std::isfinite(e.position.z)) {
            reset("unsupported");
            return;
        }
        last_packet_ = e.packet_sequence;
        if (!previous_) {
            previous_ = e;
            last_time_ = c.event.observed_ns;
            return;
        }
        auto before = *previous_;
        previous_ = e;
        double gap = combat_elapsed_ms(c.event.observed_ns, last_time_);
        last_time_ = c.event.observed_ns;
        Vector3 delta{e.position.x - before.position.x, e.position.y - before.position.y,
                      e.position.z - before.position.z};
        Vector3 prior = last_delta_;
        last_delta_ = delta;
        if (gap < 0 || gap > 150 || before.context.world_uuid != e.context.world_uuid ||
            e.context.destination_supported) {
            clear_impulse();
            return;
        }
        if (!pending_)
            return;
        if (c.event.observed_ns < sent_ || combat_elapsed_ms(c.event.observed_ns, sent_) > 2000 ||
            ++movements_ > 40) {
            clear_impulse();
            return;
        }
        if (acknowledged_)
            ++after_ack_;
        double friction = e.context.source_supported ? .546 : .91;
        double accel = e.context.source_supported
                           ? e.context.movement_speed * 1.3 * .16277136 / std::pow(.546, 3)
                           : .026;
        if (!std::isfinite(accel) || accel < 0 || accel > .5) {
            clear_impulse();
            return;
        }
        // Try every application point up to the barrier, plus two conservative movement slots.
        // A delayed/forged acknowledgement is not proof of any movement outcome.
        if (!acknowledged_ || after_ack_ <= 2) {
            Vector3 v = pending_->velocity;
            if (pending_->additive) {
                v.x += prior.x * friction;
                v.z += prior.z * friction;
                v.y += (prior.y - .08) * .98;
            }
            candidates_.push_back({v, 0});
            if (e.context.source_supported) {
                v.y = e.context.jump_velocity;
                candidates_.push_back({v, .2});
            }
        }
        std::vector<Candidate> survivors;
        for (auto candidate : candidates_) {
            double horizontal =
                std::max(0.0, std::hypot(delta.x - candidate.center.x, delta.z - candidate.center.z) -
                                  candidate.radius - accel);
            double vertical = std::abs(delta.y - candidate.center.y);
            if (horizontal > settings_.epsilon || vertical > settings_.epsilon)
                continue;
            candidate.center = {candidate.center.x * friction, (candidate.center.y - .08) * .98,
                                candidate.center.z * friction};
            candidate.radius = (candidate.radius + accel) * friction;
            survivors.push_back(candidate);
        }
        candidates_ = std::move(survivors);
        if (acknowledged_ && after_ack_ > 2 && candidates_.empty()) {
            if (samples_.add(c.event, 15000) >= settings_.alert_after) {
                c.emit("suspicious", std::string(id()), "repeated_unexplained_impulse_response",
                       {{"source", "a/yZ: horizontal/vertical incoming impulse scaling"},
                        {"impulse_token", std::to_string(pending_->token)},
                        {"kind", pending_->additive ? "explosion_add" : "velocity_replace"},
                        {"sent_x", combat_number(pending_->velocity.x)},
                        {"sent_y", combat_number(pending_->velocity.y)},
                        {"sent_z", combat_number(pending_->velocity.z)},
                        {"processing_barrier", "acknowledged"},
                        {"application_candidates", "all_eliminated"},
                        {"epsilon", combat_number(settings_.epsilon)},
                        {"samples", std::to_string(samples_.count)},
                        {"model", "collision_free_single_impulse_input_envelope"},
                        {"validation", "bounded_model_and_synthetic_tests;live_calibration_pending"}});
                samples_.clear();
            }
            clear_impulse();
        } else if (acknowledged_ && after_ack_ >= 8) {
            samples_.clear();
            clear_impulse();
        }
    }
} // namespace ac
