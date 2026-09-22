#pragma once
#include "anticheat/check.hpp"
#include "combat_common.hpp"
#include <optional>

namespace ac {
    struct TimerSettings {
        double credit_ms = 250, lead_ms = 250, sustain_ms = 500, warmup_ms = 1000;
        double maximum_queue_ms = 100, uncertain_gap_ms = 750;
    };
    class TimerCheck final : public Check {
        TimerSettings settings_;
        std::optional<std::uint64_t> last_, started_, ready_, over_;
        std::uint64_t packet_{}, count_{}, consecutive_{};
        double balance_{};
        void movement(const MovementEvent &, CheckContext &);

      public:
        explicit TimerCheck(TimerSettings);
        std::string_view id() const noexcept override {
            return "timer.budget.v1";
        }
        void registerHandlers(CheckManager &) override;
        void reset(std::string_view) override;
    };

    enum class MovementKind { speed, fly, noslow, nofall, keepsprint };
    struct MovementSettings {
        double horizontal_epsilon = .015, vertical_epsilon = .015, maximum_queue_ms = 100;
        std::uint32_t alert_after = 3;
    };
    class MovementCheck final : public Check {
        MovementKind kind_;
        MovementSettings settings_;
        std::optional<MovementEvent> previous_;
        std::optional<Vector3> delta_;
        std::uint64_t last_ns_{}, last_packet_{}, grace_until_{}, last_batch_{};
        std::optional<std::uint64_t> attack_;
        CombatSamples samples_;
        void movement(const MovementEvent &, CheckContext &);

      public:
        MovementCheck(MovementKind, MovementSettings);
        std::string_view id() const noexcept override;
        void registerHandlers(CheckManager &) override;
        void reset(std::string_view) override;
    };
} // namespace ac
