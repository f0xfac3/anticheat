/**
 * wire.cpp decodes Java observation bytes into the typed C++ events in event.hpp.
 * Schema 2 adds combat and teleport events. Schema 1 recordings remain readable.
 */

#include "wire.hpp"
#include <cstring>
#include <limits>
#include <stdexcept>

namespace ac{

    namespace{

        class Reader{
            const std::uint8_t* data_;
            std::size_t size_;
            std::size_t offset_{};

        public:
            Reader(const std::uint8_t* data, std::size_t size) : data_(data), size_(size){
                if(!data || size > 8192)
                    throw std::invalid_argument("Invalid event buffer");
            }

            // Read an unsigned little-endian integer without stepping outside the buffer.
            std::uint64_t u(unsigned count){
                if(count > 8 || size_ - offset_ < count)
                    throw std::invalid_argument("Truncated event");

                std::uint64_t result{};

                for(unsigned i = 0; i < count; ++i)
                    result |= std::uint64_t(data_[offset_++]) << (i * 8);

                return result;
            }

            std::int32_t i32(){
                auto value = u(4);
                std::int64_t result = static_cast<std::int64_t>(value);

                if(value > 0x7fffffffULL)
                    result -= 0x100000000LL;

                return static_cast<std::int32_t>(result);
            }

            double f64(){
                static_assert(sizeof(double) == 8 && std::numeric_limits<double>::is_iec559);
                auto bits = u(8);
                double value{};
                std::memcpy(&value, &bits, sizeof(value));
                return value;
            }

            std::string text(){
                auto length = static_cast<std::size_t>(u(2));

                if(length > 1024 || size_ - offset_ < length)
                    throw std::invalid_argument("Invalid string length");

                std::string value;
                value.reserve(length);

                for(std::size_t i = 0; i < length; ++i){
                    auto character = static_cast<unsigned char>(u(1));

                    if(character < 32 || character > 126)
                        throw std::invalid_argument("Non-ASCII identifier");

                    value += static_cast<char>(character);
                }

                return value;
            }

            bool flag(){
                auto value = u(1);

                if(value > 1)
                    throw std::invalid_argument("Invalid context flag");

                return value != 0;
            }

            BlockPosition position(){
                return {i32(), i32(), i32()};
            }

            MiningContext context(){
                MiningContext value;
                value.world_uuid = text();
                value.state_key = text();
                value.block = text();
                value.tool = text();
                value.unavailable_reason = text();
                value.damage_per_tick = f64();
                value.available = flag();
                return value;
            }

            Vector3 vector3(){
                return {f64(), f64(), f64()};
            }

            CombatContext combat(){
                CombatContext value;
                value.world_uuid = text();
                value.target_uuid = text();
                value.target_kind = text();
                value.unavailable_reason = text();
                value.target_id = i32();
                value.eye = vector3();
                value.target_box.minimum = vector3();
                value.target_box.maximum = vector3();
                value.ping_ms = i32();
                value.available = flag();
                return value;
            }

            void finish(){
                if(offset_ != size_)
                    throw std::invalid_argument("Trailing event bytes");
            }
        };

    }

    Event decode_event(const std::uint8_t* bytes, std::size_t size){
        Reader r(bytes, size);
        auto magic = r.u(4);
        auto version = r.u(2);

        if(magic != 0x43415846 || (version != 1 && version != 2))
            throw std::invalid_argument("Unsupported bridge schema");

        auto type = r.u(2);

        if(version == 1 && type > 6)
            throw std::invalid_argument("Combat observations require bridge schema 2");

        Event event;
        event.header = {r.u(8), r.u(8), r.u(8), r.u(8), r.u(8)};

        switch(type){
            case 1:
                event.payload = SessionStart{
                    r.text(),
                    static_cast<std::uint32_t>(r.u(4)),
                    static_cast<std::uint32_t>(r.u(4))
                };
                break;
            case 2:
                event.payload = SessionEnd{};
                break;
            case 3:
                event.payload = ResetEvent{r.text()};
                break;
            case 4:
                event.payload = TickEvent{};
                break;
            case 5:{
                DigEvent dig;
                dig.packet_sequence = r.u(8);
                dig.read_batch = r.u(8);
                dig.sampled_ns = r.u(8);
                auto action = r.u(1);
                auto face = r.u(1);

                if(action > 2 || face > 5)
                    throw std::invalid_argument("Invalid digging enum");

                dig.action = static_cast<DigAction>(action);
                dig.face = static_cast<std::uint8_t>(face);
                dig.position = r.position();
                dig.context = r.context();
                event.payload = std::move(dig);
                break;
            }
            case 6:{
                MiningContextEvent context;
                context.position = r.position();
                context.context = r.context();
                event.payload = std::move(context);
                break;
            }
            case 7:{
                AttackEvent attack;
                attack.packet_sequence = r.u(8);
                attack.read_batch = r.u(8);
                attack.sampled_ns = r.u(8);
                attack.context = r.combat();
                event.payload = std::move(attack);
                break;
            }
            case 8:{
                CombatContextEvent snapshot;
                snapshot.sampled_ns = r.u(8);
                snapshot.context = r.combat();
                event.payload = std::move(snapshot);
                break;
            }
            case 9:
                event.payload = SwingEvent{r.u(8), r.u(8)};
                break;
            case 10:{
                TeleportEvent teleport;
                teleport.cause = r.text();
                teleport.from_world = r.text();
                teleport.to_world = r.text();
                teleport.from = r.vector3();
                teleport.to = r.vector3();
                event.payload = std::move(teleport);
                break;
            }
            default:
                throw std::invalid_argument("Unknown bridge event type");
        }
        r.finish();
        return event;
    }

}
