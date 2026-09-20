/**
 * autoclicker.hpp defines the detector for unusually regular attack-request timing.
 * It does not infer physical clicks or treat high CPS alone as cheating.
 */

#pragma once
#include "anticheat/check.hpp"
#include "combat_common.hpp"
#include <vector>

namespace ac{

    struct AutoClickerSettings{
        std::uint32_t window_intervals = 40;
        double minimum_cps = 8.0;
        double maximum_cv = 0.05;
        double maximum_mad_ms = 2.5;
        double minimum_window_ms = 1500.0;
        double maximum_gap_ms = 500.0;
        double maximum_queue_ms = 100.0;
        std::uint32_t alert_after = 3;
        double sample_window_ms = 30000.0;
    };

    class AutoClickerCheck final : public Check{
        struct Previous{
            EventHeader header;
            std::uint64_t packet{};
            std::uint64_t batch{};
            std::uint64_t sampled_ns{};
            std::string world;
        };

        AutoClickerSettings settings_;
        std::optional<Previous> previous_;
        std::vector<double> intervals_;
        std::uint64_t first_event_{};
        std::uint64_t first_packet_{};
        CombatSamples samples_;

        void clear_window();
        void on_attack(const AttackEvent& event, CheckContext& ctx);
        void on_tick(const TickEvent&, CheckContext& ctx);
        void evaluate(CheckContext& ctx, const AttackEvent& event);

    public:
        explicit AutoClickerCheck(AutoClickerSettings settings);

        std::string_view id() const noexcept override{
            return "autoclicker.cadence.v1";
        }

        void registerHandlers(CheckManager& manager) override;
        void reset(std::string_view reason) override;
    };

}
