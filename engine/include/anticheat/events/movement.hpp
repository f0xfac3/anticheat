#pragma once
#include "combat.hpp"
#include <cstdint>
#include <string>

namespace ac {
    // A copied Flying-family packet and a later, pre-gameplay server snapshot.
    // Ground/contact flags below come from collision queries, never packet onGround.
    struct MovementContext {
        std::string world_uuid, unavailable_reason;
        bool available{}, source_supported{}, destination_supported{}, clear_path{}, flat_ground{};
        bool using_item{}, sprinting{};
        double movement_speed{}, friction{}, jump_velocity{};
    };

    struct MovementEvent {
        std::uint64_t packet_sequence{}, read_batch{}, sampled_ns{};
        Vector3 position;
        double yaw{}, pitch{};
        bool has_position{}, has_look{}, on_ground{};
        MovementContext context;
    };

    // Actual outgoing local-player velocity / explosion data, not Bukkit's intended velocity.
    struct ImpulseEvent {
        std::uint64_t token{};
        Vector3 velocity;
        bool additive{};
    };
    // A matching, owned transaction acknowledgement after the outgoing impulse.
    // Establishes packet processing order, not a trustworthy client clock.
    struct ImpulseAckEvent {
        std::uint64_t token{};
    };
    struct CorrectionEvent {};
} // namespace ac
