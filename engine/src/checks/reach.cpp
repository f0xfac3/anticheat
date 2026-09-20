/**
 * reach.cpp detects repeated out-of-range attack requests in stationary lab conditions.
 * This is not a reconstruction of the client's historical view or a full moving-combat check.
 */

#include "reach.hpp"
#include <algorithm>
#include <cmath>
#include <stdexcept>

namespace ac{

    namespace{

        bool finite(const Vector3& point){
            return std::isfinite(point.x) && std::isfinite(point.y) && std::isfinite(point.z)
                && std::abs(point.x) <= 32000000 && std::abs(point.y) <= 32000000
                && std::abs(point.z) <= 32000000;
        }

        double span(const Vector3& a, const Vector3& b){
            double x = b.x - a.x;
            double y = b.y - a.y;
            double z = b.z - a.z;
            return std::sqrt(x * x + y * y + z * z);
        }

        void include(BoundingBox& box, const Vector3& point){
            box.minimum.x = std::min(box.minimum.x, point.x);
            box.minimum.y = std::min(box.minimum.y, point.y);
            box.minimum.z = std::min(box.minimum.z, point.z);
            box.maximum.x = std::max(box.maximum.x, point.x);
            box.maximum.y = std::max(box.maximum.y, point.y);
            box.maximum.z = std::max(box.maximum.z, point.z);
        }

        double axis_gap(double a_min, double a_max, double b_min, double b_max){
            return std::max({0.0, b_min - a_max, a_min - b_max});
        }

        // Shortest distance between the sampled eye envelope and the expanded target envelope.
        // A shortest-distance bound cannot be made larger by aiming away from the target.
        double minimum_distance(const BoundingBox& eyes, BoundingBox target, double padding){
            target.minimum.x -= padding;
            target.minimum.y -= padding;
            target.minimum.z -= padding;
            target.maximum.x += padding;
            target.maximum.y += padding;
            target.maximum.z += padding;

            double x = axis_gap(eyes.minimum.x, eyes.maximum.x, target.minimum.x, target.maximum.x);
            double y = axis_gap(eyes.minimum.y, eyes.maximum.y, target.minimum.y, target.maximum.y);
            double z = axis_gap(eyes.minimum.z, eyes.maximum.z, target.minimum.z, target.maximum.z);
            return std::sqrt(x * x + y * y + z * z);
        }

        bool range(double value, double minimum, double maximum){
            return std::isfinite(value) && value >= minimum && value <= maximum;
        }

    }

    ReachCheck::ReachCheck(ReachSettings settings) : settings_(settings){
        if(!range(settings.maximum_distance, 1.0, 6.0)
            || !range(settings.distance_grace, 0.0, 1.0)
            || !range(settings.box_padding, 0.1, 1.0)
            || !range(settings.history_ms, 500.0, 1500.0)
            || !range(settings.maximum_motion, 0.0, 0.1)
            || !range(settings.maximum_ping_ms, 0.0, 250.0)
            || !range(settings.maximum_queue_ms, 1.0, 250.0)
            || !range(settings.maximum_frame_gap_ms, 50.0, 250.0)
            || !range(settings.sample_window_ms, 1000.0, 60000.0)
            || settings.alert_after == 0 || settings.alert_after > 100){
            throw std::invalid_argument("Invalid Reach settings");
        }
    }

    void ReachCheck::registerHandlers(CheckManager& manager){
        manager.on<AttackEvent>(
            [this](const AttackEvent& event, CheckContext& ctx){
                on_attack(event, ctx);
            }
        );
        manager.on<CombatContextEvent>(
            [this](const CombatContextEvent& event, CheckContext& ctx){
                on_context(event, ctx);
            }
        );
        manager.on<TickEvent>(
            [this](const TickEvent& event, CheckContext& ctx){
                on_tick(event, ctx);
            }
        );
        manager.on<TeleportEvent>(
            [this](const TeleportEvent&, CheckContext&){
                // A teleport invalidates geometry, not earlier completed suspicious comparisons.
                history_.clear();
                last_batch_.reset();
            }
        );
    }

    void ReachCheck::reset(std::string_view){
        history_.clear();
        samples_.clear();
        last_packet_ = 0;
        last_batch_.reset();
    }

    bool ReachCheck::usable(const CombatContext& context) const{
        const auto& box = context.target_box;
        return context.available && !context.world_uuid.empty() && !context.target_uuid.empty()
            && context.ping_ms >= 0 && context.ping_ms <= settings_.maximum_ping_ms
            && finite(context.eye) && finite(box.minimum) && finite(box.maximum)
            && box.maximum.x > box.minimum.x && box.maximum.y > box.minimum.y
            && box.maximum.z > box.minimum.z && span(box.minimum, box.maximum) < 128;
    }

    // Keep bounded snapshots for only the recently attacked entities.
    void ReachCheck::remember(const CombatContext& context, std::uint64_t time, const EventHeader& header){
        if(!usable(context)){
            history_.erase(context.target_uuid);
            return;
        }

        if(!history_.count(context.target_uuid) && history_.size() >= 8){
            auto oldest = std::min_element(
                history_.begin(),
                history_.end(),
                [](const auto& a, const auto& b){
                    return a.second.back().time < b.second.back().time;
                }
            );
            history_.erase(oldest);
        }

        auto& frames = history_[context.target_uuid];

        if(!frames.empty()){
            const auto& previous = frames.back();
            double gap = combat_elapsed_ms(time, previous.time);

            if(gap < 0 || gap > settings_.maximum_frame_gap_ms
                || previous.context.world_uuid != context.world_uuid
                || previous.context.target_id != context.target_id){
                frames.clear();
            }
        }

        frames.push_back({time, header.ordinal, context});

        // Retain the frame immediately before the history boundary as well.
        while(frames.size() > 1
            && combat_elapsed_ms(time, frames[1].time) >= settings_.history_ms){
            frames.pop_front();
        }

        while(frames.size() > 64)
            frames.pop_front();
    }

    void ReachCheck::on_context(const CombatContextEvent& event, CheckContext& ctx){
        if(!combat_model(ctx.player.identity))
            return;

        remember(event.context, event.sampled_ns, ctx.event);
    }

    void ReachCheck::on_tick(const TickEvent&, CheckContext& ctx){
        samples_.expire(ctx.event.observed_ns, settings_.sample_window_ms);

        for(auto it = history_.begin(); it != history_.end();){
            double age = combat_elapsed_ms(ctx.event.observed_ns, it->second.back().time);

            if(age < 0 || age > 3000)
                it = history_.erase(it);
            else
                ++it;
        }
    }

    void ReachCheck::on_attack(const AttackEvent& event, CheckContext& ctx){
        if(!combat_model(ctx.player.identity))
            return;

        auto skip = [&](const char* reason){
            ctx.emit(
                "trace",
                std::string(id()),
                "reach_skipped",
                {{"reason", reason}, {"target", event.context.target_uuid}}
            );
        };

        if(event.packet_sequence <= last_packet_){
            skip("duplicate_or_out_of_order_packet");
            return;
        }

        last_packet_ = event.packet_sequence;
        double queued = combat_elapsed_ms(event.sampled_ns, ctx.event.observed_ns);

        if(queued < 0 || queued > settings_.maximum_queue_ms){
            history_.clear();
            skip("delayed_snapshot");
            return;
        }

        if(!usable(event.context)){
            history_.erase(event.context.target_uuid);
            skip(event.context.available ? "unsupported_snapshot_or_latency" : event.context.unavailable_reason.c_str());
            return;
        }

        remember(event.context, event.sampled_ns, ctx.event);
        const auto& frames = history_.at(event.context.target_uuid);

        if(frames.size() < 4
            || combat_elapsed_ms(event.sampled_ns, frames.front().time) < settings_.history_ms){
            skip("history_warming");
            return;
        }

        BoundingBox eyes{frames.front().context.eye, frames.front().context.eye};
        BoundingBox minima{frames.front().context.target_box.minimum, frames.front().context.target_box.minimum};
        BoundingBox maxima{frames.front().context.target_box.maximum, frames.front().context.target_box.maximum};
        BoundingBox target = frames.front().context.target_box;

        for(const auto& frame : frames){
            include(eyes, frame.context.eye);
            include(minima, frame.context.target_box.minimum);
            include(maxima, frame.context.target_box.maximum);
            include(target, frame.context.target_box.minimum);
            include(target, frame.context.target_box.maximum);
        }

        // Do not apply a stationary model to moving players, changing poses, or moving targets.
        if(span(eyes.minimum, eyes.maximum) > settings_.maximum_motion
            || span(minima.minimum, minima.maximum) > settings_.maximum_motion
            || span(maxima.minimum, maxima.maximum) > settings_.maximum_motion){
            skip("moving_player_or_target");
            return;
        }

        // Do not count a delivery burst as multiple independent reach samples.
        if(last_batch_ && event.read_batch <= *last_batch_){
            skip("same_or_invalid_read_batch");
            return;
        }

        last_batch_ = event.read_batch;
        double distance = minimum_distance(eyes, target, settings_.box_padding);
        double threshold = settings_.maximum_distance + settings_.distance_grace;
        bool suspicious = distance > threshold;

        Evidence evidence{
            {"target", event.context.target_uuid},
            {"target_id", std::to_string(event.context.target_id)},
            {"target_kind", event.context.target_kind},
            {"world", event.context.world_uuid},
            {"packet", std::to_string(event.packet_sequence)},
            {"history_start_event", std::to_string(frames.front().ordinal)},
            {"history_ms", combat_number(combat_elapsed_ms(event.sampled_ns, frames.front().time))},
            {"minimum_distance", combat_number(distance)},
            {"allowed_distance", combat_number(threshold)},
            {"box_padding", combat_number(settings_.box_padding)},
            {"ping_ms", std::to_string(event.context.ping_ms)},
            {"queue_ms", combat_number(queued)},
            {"geometry", "stationary_server_snapshots"},
            {"client_view", "NOT_RECONSTRUCTED"},
            {"server_attack_outcome", "NOT_MEASURED"}
        };

        ctx.emit(
            "trace",
            std::string(id()),
            suspicious ? "reach_suspicious_sample" : "reach_within_bound",
            evidence
        );

        if(!suspicious){
            samples_.clear();
            return;
        }

        if(samples_.add(ctx.event, settings_.sample_window_ms) >= settings_.alert_after){
            evidence.emplace_back("samples", std::to_string(samples_.count));
            evidence.emplace_back("first_sample_event", std::to_string(samples_.first_event));
            ctx.emit(
                "suspicious",
                std::string(id()),
                "repeated_out_of_range_attack_requests",
                std::move(evidence)
            );
            samples_.clear();
        }
    }

}
