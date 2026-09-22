/**
 * combat.hpp defines combat observations collected by the Java adapter.
 * These are requests and sampled server state, not confirmed hits or mouse input.
 */

#pragma once
#include <cstdint>
#include <string>

namespace ac{

    struct Vector3{
        double x{}, y{}, z{};
    };

    // Unexpanded server collision box. The check owns any modeling allowance.
    struct BoundingBox{
        Vector3 minimum;
        Vector3 maximum;
    };

    // Server snapshot associated with an attack or a periodically watched target.
    struct CombatContext{
        std::string world_uuid;
        std::string target_uuid;
        std::string target_kind;
        std::string unavailable_reason;
        std::int32_t target_id{};
        Vector3 eye;
        BoundingBox target_box;
        std::int32_t ping_ms{}; // Server's latency estimate, not exact packet delay.
        bool available{};
        double yaw{}, pitch{};
        bool rotation_available{}, attacker_sprinting{}, target_player{};
    };

    // Client ATTACK request plus the state sampled before normal attack handling.
    struct AttackEvent{
        std::uint64_t packet_sequence{};
        std::uint64_t read_batch{};
        std::uint64_t sampled_ns{};
        CombatContext context;
    };

    // Fresh snapshot of the player and a recently attacked entity.
    struct CombatContextEvent{
        std::uint64_t sampled_ns{};
        CombatContext context;
    };

    // An arm-animation packet is not proof of a physical mouse click.
    struct SwingEvent{
        std::uint64_t packet_sequence{};
        std::uint64_t read_batch{};
    };

    // Server-recognized teleport. Checks invalidate only the state it affects.
    struct TeleportEvent{
        std::string cause;
        std::string from_world;
        std::string to_world;
        Vector3 from;
        Vector3 to;
    };

}
