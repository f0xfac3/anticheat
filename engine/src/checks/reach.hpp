/**
 * reach.hpp defines the first Reach detector.
 * It compares attacks only after the sampled player and target have stayed still.
 */

#pragma once
#include "anticheat/check.hpp"
#include "combat_common.hpp"
#include <deque>
#include <unordered_map>

namespace ac{

    struct ReachSettings{
        double maximum_distance = 3.0;
        double distance_grace = 0.05;
        double box_padding = 0.13125; // 0.1 picking border + 1/32 position encoding allowance.
        double history_ms = 750.0;
        double maximum_motion = 0.03;
        double maximum_ping_ms = 150.0;
        double maximum_queue_ms = 100.0;
        double maximum_frame_gap_ms = 150.0;
        std::uint32_t alert_after = 3;
        double sample_window_ms = 10000.0;
    };

    class ReachCheck final : public Check{
        struct Frame{
            std::uint64_t time{};
            std::uint64_t ordinal{};
            CombatContext context;
        };

        ReachSettings settings_;
        std::unordered_map<std::string, std::deque<Frame>> history_;
        CombatSamples samples_;
        std::uint64_t last_packet_{};
        std::optional<std::uint64_t> last_batch_;

        bool usable(const CombatContext& context) const;
        void remember(const CombatContext& context, std::uint64_t time, const EventHeader& header);
        void on_context(const CombatContextEvent& event, CheckContext& ctx);
        void on_attack(const AttackEvent& event, CheckContext& ctx);
        void on_tick(const TickEvent&, CheckContext& ctx);

    public:
        explicit ReachCheck(ReachSettings settings);

        std::string_view id() const noexcept override{
            return "reach.stationary.v1";
        }

        void registerHandlers(CheckManager& manager) override;
        void reset(std::string_view reason) override;
    };

}
