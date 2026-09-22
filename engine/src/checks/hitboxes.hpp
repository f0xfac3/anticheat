#pragma once
#include "anticheat/check.hpp"
#include "combat_common.hpp"
#include <deque>
#include <unordered_map>

namespace ac {
    struct HitboxSettings {
        double padding = .13125, angle_grace = 3, history_ms = 750;
        std::uint32_t alert_after = 3;
    };
    class HitboxCheck final : public Check {
        struct Frame {
            std::uint64_t time;
            CombatContext state;
        };
        struct Pending {
            std::uint64_t time;
            AttackEvent attack;
        };
        HitboxSettings settings_;
        std::unordered_map<std::string, std::deque<Frame>> history_;
        std::deque<Pending> pending_;
        CombatSamples samples_;
        std::uint64_t packet_{}, batch_{};
        void remember(const CombatContext &, std::uint64_t);
        void evaluate(CheckContext &);

      public:
        explicit HitboxCheck(HitboxSettings);
        std::string_view id() const noexcept override {
            return "hitboxes.stable_ray.v1";
        }
        void registerHandlers(CheckManager &) override;
        void reset(std::string_view) override;
    };
} // namespace ac
