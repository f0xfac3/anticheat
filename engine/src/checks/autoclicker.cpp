/**
 * autoclicker.cpp measures attack intervals and looks for persistent low variation.
 * Its result is a timing anomaly, not a calibrated probability of automation.
 */

#include "autoclicker.hpp"
#include <algorithm>
#include <cmath>
#include <numeric>
#include <stdexcept>

namespace ac{

    namespace{

        double median(std::vector<double> values){
            std::sort(values.begin(), values.end());
            std::size_t middle = values.size() / 2;

            if(values.size() % 2)
                return values[middle];

            return (values[middle - 1] + values[middle]) / 2;
        }

        bool range(double value, double minimum, double maximum){
            return std::isfinite(value) && value >= minimum && value <= maximum;
        }

    }

    AutoClickerCheck::AutoClickerCheck(AutoClickerSettings settings) : settings_(settings){
        if(settings.window_intervals < 16 || settings.window_intervals > 256
            || !range(settings.minimum_cps, 1, 25)
            || !range(settings.maximum_cv, 0.001, 0.25)
            || !range(settings.maximum_mad_ms, 0, 20)
            || !range(settings.minimum_window_ms, 1000, 10000)
            || !range(settings.maximum_gap_ms, 100, 3000)
            || !range(settings.maximum_queue_ms, 1, 250)
            || settings.alert_after == 0 || settings.alert_after > 100
            || !range(settings.sample_window_ms, 1000, 120000)){
            throw std::invalid_argument("Invalid Autoclicker settings");
        }

        intervals_.reserve(settings.window_intervals);
    }

    void AutoClickerCheck::registerHandlers(CheckManager& manager){
        manager.on<AttackEvent>(
            [this](const AttackEvent& event, CheckContext& ctx){
                on_attack(event, ctx);
            }
        );
        manager.on<TickEvent>(
            [this](const TickEvent& event, CheckContext& ctx){
                on_tick(event, ctx);
            }
        );
        manager.on<TeleportEvent>(
            [this](const TeleportEvent&, CheckContext&){
                // Do not measure an interval across a teleport. Completed evidence can still expire normally.
                clear_window();
            }
        );
    }

    void AutoClickerCheck::clear_window(){
        previous_.reset();
        intervals_.clear();
        first_event_ = 0;
        first_packet_ = 0;
    }

    void AutoClickerCheck::reset(std::string_view){
        clear_window();
        samples_.clear();
    }

    void AutoClickerCheck::on_tick(const TickEvent&, CheckContext& ctx){
        samples_.expire(ctx.event.observed_ns, settings_.sample_window_ms);

        if(previous_){
            double idle = combat_elapsed_ms(ctx.event.observed_ns, previous_->header.observed_ns);

            if(idle < 0 || idle > settings_.maximum_gap_ms)
                clear_window();
        }
    }

    void AutoClickerCheck::on_attack(const AttackEvent& event, CheckContext& ctx){
        if(!combat_model(ctx.player.identity))
            return;

        double queued = combat_elapsed_ms(event.sampled_ns, ctx.event.observed_ns);

        if(!event.context.available || event.context.world_uuid.empty()
            || queued < 0 || queued > settings_.maximum_queue_ms){
            clear_window();
            return;
        }

        Previous current{
            ctx.event,
            event.packet_sequence,
            event.read_batch,
            event.sampled_ns,
            event.context.world_uuid
        };

        if(!previous_){
            previous_ = std::move(current);
            return;
        }

        double interval = combat_elapsed_ms(ctx.event.observed_ns, previous_->header.observed_ns);
        double sampled_interval = combat_elapsed_ms(event.sampled_ns, previous_->sampled_ns);

        // Burst delivery, stale snapshots, and long pauses are not usable click timing.
        if(event.packet_sequence <= previous_->packet || event.read_batch <= previous_->batch
            || interval < 1 || interval > settings_.maximum_gap_ms || sampled_interval < 0
            || std::abs(interval - sampled_interval) > settings_.maximum_queue_ms
            || event.context.world_uuid != previous_->world){
            clear_window();
            previous_ = std::move(current);
            return;
        }

        if(intervals_.empty()){
            first_event_ = previous_->header.ordinal;
            first_packet_ = previous_->packet;
        }

        intervals_.push_back(interval);
        previous_ = std::move(current);

        if(intervals_.size() == settings_.window_intervals){
            evaluate(ctx, event);
            intervals_.clear(); // Each completed window contributes at most one sample.
        }
    }

    void AutoClickerCheck::evaluate(CheckContext& ctx, const AttackEvent& event){
        double duration = std::accumulate(intervals_.begin(), intervals_.end(), 0.0);
        double count = static_cast<double>(intervals_.size());
        double mean = duration / count;
        double cps = 1000.0 / mean;
        double squared = 0;
        double center = median(intervals_);
        std::vector<double> deviations;
        std::size_t tick_aligned = 0;

        for(double interval : intervals_){
            squared += (interval - mean) * (interval - mean);
            deviations.push_back(std::abs(interval - center));

            if(std::abs(interval - std::round(interval / 50.0) * 50.0) <= 2.0)
                ++tick_aligned;
        }

        double standard_deviation = std::sqrt(squared / count);
        double cv = standard_deviation / mean;
        double mad = median(std::move(deviations));
        bool quantized = static_cast<double>(tick_aligned) / count >= 0.9;

        // Perfect 50/100 ms steps can come from tick batching; do not call that automation.
        bool eligible = duration >= settings_.minimum_window_ms
            && cps >= settings_.minimum_cps && !quantized;
        bool suspicious = eligible && cv <= settings_.maximum_cv && mad <= settings_.maximum_mad_ms;

        Evidence evidence{
            {"source", "attack_requests"},
            {"first_event", std::to_string(first_event_)},
            {"first_packet", std::to_string(first_packet_)},
            {"last_packet", std::to_string(event.packet_sequence)},
            {"intervals", std::to_string(intervals_.size())},
            {"window_ms", combat_number(duration)},
            {"attack_cps", combat_number(cps)},
            {"mean_interval_ms", combat_number(mean)},
            {"stddev_ms", combat_number(standard_deviation)},
            {"cv", combat_number(cv)},
            {"mad_ms", combat_number(mad)},
            {"maximum_cv", combat_number(settings_.maximum_cv)},
            {"maximum_mad_ms", combat_number(settings_.maximum_mad_ms)},
            {"tick_quantized", quantized ? "true" : "false"},
            {"physical_input", "NOT_OBSERVED"},
            {"probability", "NOT_CALIBRATED"}
        };

        ctx.emit(
            "trace",
            std::string(id()),
            suspicious ? "attack_cadence_suspicious_window" : "attack_cadence_no_flag",
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
                "repeated_regular_attack_cadence",
                std::move(evidence)
            );
            samples_.clear();
        }
    }

}
