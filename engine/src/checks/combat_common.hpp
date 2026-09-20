/**
 * combat_common.hpp contains the small measurement helpers shared by combat checks.
 */

#pragma once
#include "anticheat/event.hpp"
#include <cmath>
#include <iomanip>
#include <locale>
#include <optional>
#include <sstream>

namespace ac{

    inline double combat_elapsed_ms(std::uint64_t end, std::uint64_t start){
        if(end < start)
            return -1.0;

        return static_cast<double>(end - start) / 1e6;
    }

    inline std::string combat_number(double value){
        std::ostringstream out;
        out.imbue(std::locale::classic());
        out << std::fixed << std::setprecision(3) << value;
        return out.str();
    }

    inline bool combat_model(const SessionStart& identity){
        return identity.client_protocol == 47 && identity.server_model == 10808;
    }

    // Count qualifying samples inside a window anchored at its first sample.
    // Expiration is not extended indefinitely by later samples.
    struct CombatSamples{
        std::uint32_t count{};
        std::optional<std::uint64_t> first_ns;
        std::uint64_t first_event{};

        void clear(){
            count = 0;
            first_ns.reset();
            first_event = 0;
        }

        void expire(std::uint64_t now, double window_ms){
            if(first_ns && (now < *first_ns || combat_elapsed_ms(now, *first_ns) > window_ms))
                clear();
        }

        std::uint32_t add(const EventHeader& event, double window_ms){
            expire(event.observed_ns, window_ms);

            if(!first_ns){
                first_ns = event.observed_ns;
                first_event = event.ordinal;
            }

            return ++count;
        }
    };

}
