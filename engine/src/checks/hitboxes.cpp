#include "hitboxes.hpp"
#include <algorithm>
#include <array>
#include <cmath>
#include <stdexcept>

namespace ac {
    namespace {
        constexpr double pi = 3.14159265358979323846;
        Vector3 look(double yaw, double pitch) {
            yaw *= pi / 180;
            pitch *= pi / 180;
            return {-std::sin(yaw) * std::cos(pitch), -std::sin(pitch), std::cos(yaw) * std::cos(pitch)};
        }
        double distance(const Vector3 &a, const Vector3 &b) {
            return std::hypot(std::hypot(a.x - b.x, a.z - b.z), a.y - b.y);
        }
        bool valid(const CombatContext &c) {
            auto point = [](const Vector3 &v) {
                return std::isfinite(v.x) && std::isfinite(v.y) && std::isfinite(v.z) &&
                       std::abs(v.x) < 3e7 && std::abs(v.y) < 2048 && std::abs(v.z) < 3e7;
            };
            return c.available && c.rotation_available && !c.world_uuid.empty() && !c.target_uuid.empty() &&
                   c.ping_ms >= 0 && c.ping_ms <= 75 && point(c.eye) && point(c.target_box.minimum) &&
                   point(c.target_box.maximum) && c.target_box.maximum.x > c.target_box.minimum.x &&
                   c.target_box.maximum.y > c.target_box.minimum.y &&
                   c.target_box.maximum.z > c.target_box.minimum.z &&
                   distance(c.target_box.minimum, c.target_box.maximum) < 4 && std::isfinite(c.yaw) &&
                   std::isfinite(c.pitch) && std::abs(c.pitch) <= 90;
        }
        bool intersects(const Vector3 &eye, const Vector3 &direction, BoundingBox box, double padding) {
            std::array<double, 3> e{eye.x, eye.y, eye.z}, d{direction.x, direction.y, direction.z};
            std::array<double, 3> lo{box.minimum.x - padding, box.minimum.y - padding,
                                     box.minimum.z - padding};
            std::array<double, 3> hi{box.maximum.x + padding, box.maximum.y + padding,
                                     box.maximum.z + padding};
            double near = 0, far = 3.05;
            for (std::size_t i = 0; i < 3; ++i) {
                if (std::abs(d[i]) < 1e-10) {
                    if (e[i] < lo[i] || e[i] > hi[i])
                        return false;
                    continue;
                }
                double a = (lo[i] - e[i]) / d[i], b = (hi[i] - e[i]) / d[i];
                if (a > b)
                    std::swap(a, b);
                near = std::max(near, a);
                far = std::min(far, b);
                if (near > far)
                    return false;
            }
            return true;
        }
    } // namespace
    HitboxCheck::HitboxCheck(HitboxSettings s) : settings_(s) {
        if (!std::isfinite(s.padding) || s.padding < .1 || s.padding > .5 || !std::isfinite(s.angle_grace) ||
            s.angle_grace < 1 || s.angle_grace > 10 || !std::isfinite(s.history_ms) || s.history_ms < 500 ||
            s.history_ms > 1500 || s.alert_after < 2 || s.alert_after > 100)
            throw std::invalid_argument("Invalid HitBoxes settings");
    }
    void HitboxCheck::reset(std::string_view) {
        history_.clear();
        pending_.clear();
        samples_.clear();
        packet_ = batch_ = 0;
    }
    void HitboxCheck::remember(const CombatContext &state, std::uint64_t now) {
        if (!valid(state)) {
            history_.erase(state.target_uuid);
            return;
        }
        if (!history_.count(state.target_uuid) && history_.size() >= 8) {
            history_.clear();
            pending_.clear();
        }
        auto &frames = history_[state.target_uuid];
        if (!frames.empty() &&
            (now < frames.back().time || combat_elapsed_ms(now, frames.back().time) > 150 ||
             frames.back().state.world_uuid != state.world_uuid ||
             frames.back().state.target_id != state.target_id))
            frames.clear();
        frames.push_back({now, state});
        while (frames.size() > 1 && combat_elapsed_ms(now, frames[1].time) > settings_.history_ms + 200)
            frames.pop_front();
        while (frames.size() > 48)
            frames.pop_front();
    }
    void HitboxCheck::registerHandlers(CheckManager &m) {
        m.on<CombatContextEvent>([this](const CombatContextEvent &e, CheckContext &c) {
            if (!combat_model(c.player.identity))
                return;
            remember(e.context, e.sampled_ns);
            evaluate(c);
        });
        m.on<AttackEvent>([this](const AttackEvent &e, CheckContext &c) {
            double age = combat_elapsed_ms(e.sampled_ns, c.event.observed_ns);
            if (!combat_model(c.player.identity) || e.packet_sequence <= packet_ || e.read_batch <= batch_ ||
                age < 0 || age > 100)
                return;
            packet_ = e.packet_sequence;
            batch_ = e.read_batch;
            remember(e.context, e.sampled_ns);
            if (valid(e.context) && pending_.size() < 16)
                pending_.push_back({c.event.observed_ns, e});
        });
        m.on<TickEvent>([this](const auto &, auto &c) { evaluate(c); });
        auto clear = [this](const auto &, auto &) { reset("boundary"); };
        m.on<TeleportEvent>(clear);
        m.on<CorrectionEvent>(clear);
    }
    void HitboxCheck::evaluate(CheckContext &c) {
        while (!pending_.empty()) {
            auto pending = pending_.front();
            double age = combat_elapsed_ms(c.event.observed_ns, pending.time);
            if (age >= 0 && age < 100)
                return;
            pending_.pop_front();
            if (age < 0 || age > 300)
                continue;
            const auto &a = pending.attack.context;
            auto found = history_.find(a.target_uuid);
            Vector3 nearest{std::clamp(a.eye.x, a.target_box.minimum.x - settings_.padding,
                                       a.target_box.maximum.x + settings_.padding),
                            std::clamp(a.eye.y, a.target_box.minimum.y - settings_.padding,
                                       a.target_box.maximum.y + settings_.padding),
                            std::clamp(a.eye.z, a.target_box.minimum.z - settings_.padding,
                                       a.target_box.maximum.z + settings_.padding)};
            if (distance(a.eye, nearest) > 3.05)
                continue; // Reach owns out-of-range requests.
            if (found == history_.end())
                continue;
            const auto &frames = found->second;
            if (frames.empty() || frames.front().time > pending.time ||
                combat_elapsed_ms(pending.time, frames.front().time) < settings_.history_ms ||
                frames.back().time < pending.time + 100000000ULL)
                continue;
            bool stable = true, hit = false;
            Vector3 direction = look(a.yaw, a.pitch);
            // Inflate by the cone displacement as well as vanilla picking/encoding margins.
            double padding = settings_.padding + 3.05 * std::sin(settings_.angle_grace * pi / 180) + .03;
            for (const auto &f : frames) {
                const auto &b = f.state;
                if (b.world_uuid != a.world_uuid || b.target_id != a.target_id ||
                    distance(a.eye, b.eye) > .03 ||
                    distance(a.target_box.minimum, b.target_box.minimum) > .03 ||
                    distance(a.target_box.maximum, b.target_box.maximum) > .03 ||
                    distance(direction, look(b.yaw, b.pitch)) > 2 * std::sin(.5 * pi / 180)) {
                    stable = false;
                    break;
                }
                if (intersects(b.eye, look(b.yaw, b.pitch), b.target_box, padding))
                    hit = true;
            }
            if (!stable)
                continue;
            if (hit) {
                samples_.clear();
                continue;
            }
            if (samples_.add(c.event, 10000) >= settings_.alert_after) {
                c.emit("suspicious", std::string(id()), "repeated_stable_aim_target_box_misses",
                       {{"source", "a/yY + a/OR: enlarged entity selection geometry"},
                        {"target", a.target_uuid},
                        {"attack_packet", std::to_string(pending.attack.packet_sequence)},
                        {"padding_with_angular_uncertainty", combat_number(padding)},
                        {"samples", std::to_string(samples_.count)},
                        {"model", "stationary_stable_aim_with_future_samples"},
                        {"client_view", "NOT_RECONSTRUCTED"},
                        {"validation", "mechanism_and_synthetic_tests;live_calibration_pending"}});
                samples_.clear();
            }
        }
    }
} // namespace ac
