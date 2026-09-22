#pragma once
#include "anticheat/check.hpp"
#include "combat_common.hpp"
#include <optional>

namespace ac {
    struct VelocitySettings {
        double epsilon = .025, maximum_queue_ms = 100;
        std::uint32_t alert_after = 3;
    };
    class VelocityCheck final : public Check {
        struct Candidate {
            Vector3 center;
            double radius{};
        };
        VelocitySettings settings_;
        std::optional<MovementEvent> previous_;
        Vector3 last_delta_{};
        std::optional<ImpulseEvent> pending_;
        std::vector<Candidate> candidates_;
        CombatSamples samples_;
        std::uint64_t sent_{}, last_packet_{}, last_time_{};
        unsigned after_ack_{}, movements_{};
        bool acknowledged_{};
        void movement(const MovementEvent &, CheckContext &);
        void clear_impulse();

      public:
        explicit VelocityCheck(VelocitySettings);
        std::string_view id() const noexcept override {
            return "velocity.response.v1";
        }
        void registerHandlers(CheckManager &) override;
        void reset(std::string_view) override;
    };
} // namespace ac
