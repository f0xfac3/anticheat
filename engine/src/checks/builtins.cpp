/**
 * builtins.cpp constructs and configures the detection checks.
 * Built-in checks are the detectors shipped with the anticheat.
 */

#include "anticheat/builtins.hpp"
#include "fastbreak.hpp"
#include "reach.hpp"
#include "autoclicker.hpp"
#include <cmath>
#include <locale>
#include <map>
#include <sstream>
#include <stdexcept>

namespace ac{

    namespace{

        std::string trim(std::string text){
            auto first = text.find_first_not_of(" \t\r\n");

            if(first == std::string::npos)
                return {};

            auto last = text.find_last_not_of(" \t\r\n");
            return text.substr(first, last - first + 1);
        }

    }

    EngineSetup builtin_checks(std::string_view configuration){
        if(configuration.size() > 16384)
            throw std::invalid_argument("Configuration too large");

        std::istringstream input{std::string(configuration)};
        std::map<std::string, std::string> values;
        std::string line;

        // Parse settings once. Each detector consumes the keys it owns below.
        while(std::getline(input, line)){
            line = trim(line);

            if(line.empty() || line.front() == '#')
                continue;

            auto eq = line.find('=');

            if(eq == std::string::npos)
                throw std::invalid_argument("Expected key=value");

            auto key = trim(line.substr(0, eq));
            auto value = trim(line.substr(eq + 1));

            if(key.empty())
                throw std::invalid_argument("Empty setting name");

            if(!values.emplace(key, value).second)
                throw std::invalid_argument("Duplicate setting: " + key);
        }

        // Read and consume a boolean setting.
        auto boolean = [&](const std::string& key, bool fallback){
            auto found = values.find(key);

            if(found == values.end())
                return fallback;

            auto value = found->second;
            values.erase(found);

            if(value == "true")
                return true;

            if(value == "false")
                return false;

            throw std::invalid_argument("Expected true/false: " + key);
        };

        // Read, validate, and consume a numeric setting.
        auto number = [&](const std::string& key, double fallback){
            auto found = values.find(key);

            if(found == values.end())
                return fallback;

            std::istringstream stream(found->second);
            stream.imbue(std::locale::classic());
            double value{};
            stream >> value;

            if(!stream || !std::isfinite(value))
                throw std::invalid_argument("Invalid number: " + key);

            stream >> std::ws;

            if(!stream.eof())
                throw std::invalid_argument("Trailing number data: " + key);

            values.erase(found);
            return value;
        };

        // Reject fractional or out-of-range counts before converting to uint32_t.
        auto integer = [&](const std::string& key, std::uint32_t fallback, double maximum){
            double value = number(key, fallback);

            if(value < 1 || value > maximum || std::floor(value) != value)
                throw std::invalid_argument("Invalid integer: " + key);

            return static_cast<std::uint32_t>(value);
        };

        EngineSetup setup{
            boolean("trace", true),
            {}
        };

        bool fastbreak_enabled = boolean("fastbreak.enabled", true);
        FastBreakSettings settings;
        settings.maximum_ratio = number(
            "fastbreak.maximum_ratio",
            settings.maximum_ratio
        );
        settings.grace_ms = number(
            "fastbreak.grace_ms",
            settings.grace_ms
        );
        settings.minimum_expected_ms = number(
            "fastbreak.minimum_expected_ms",
            settings.minimum_expected_ms
        );
        settings.maximum_queue_ms = number(
            "fastbreak.maximum_queue_ms",
            settings.maximum_queue_ms
        );
        settings.sample_window_ms = number(
            "fastbreak.sample_window_ms",
            settings.sample_window_ms
        );
        settings.alert_after = integer(
            "fastbreak.alert_after",
            settings.alert_after,
            10000
        );

        // Validate settings even when the check is disabled.
        (void)FastBreakCheck(settings);

        bool reach_enabled = boolean("reach.enabled", true);
        ReachSettings reach;
        reach.maximum_distance = number(
            "reach.maximum_distance",
            reach.maximum_distance
        );
        reach.distance_grace = number(
            "reach.distance_grace",
            reach.distance_grace
        );
        reach.box_padding = number(
            "reach.box_padding",
            reach.box_padding
        );
        reach.history_ms = number(
            "reach.history_ms",
            reach.history_ms
        );
        reach.maximum_motion = number(
            "reach.maximum_motion",
            reach.maximum_motion
        );
        reach.maximum_ping_ms = number(
            "reach.maximum_ping_ms",
            reach.maximum_ping_ms
        );
        reach.maximum_queue_ms = number(
            "reach.maximum_queue_ms",
            reach.maximum_queue_ms
        );
        reach.maximum_frame_gap_ms = number(
            "reach.maximum_frame_gap_ms",
            reach.maximum_frame_gap_ms
        );
        reach.sample_window_ms = number(
            "reach.sample_window_ms",
            reach.sample_window_ms
        );
        reach.alert_after = integer(
            "reach.alert_after",
            reach.alert_after,
            100
        );
        (void)ReachCheck(reach);

        bool autoclicker_enabled = boolean("autoclicker.enabled", true);
        AutoClickerSettings autoclicker;
        autoclicker.minimum_cps = number(
            "autoclicker.minimum_cps",
            autoclicker.minimum_cps
        );
        autoclicker.maximum_cv = number(
            "autoclicker.maximum_cv",
            autoclicker.maximum_cv
        );
        autoclicker.maximum_mad_ms = number(
            "autoclicker.maximum_mad_ms",
            autoclicker.maximum_mad_ms
        );
        autoclicker.minimum_window_ms = number(
            "autoclicker.minimum_window_ms",
            autoclicker.minimum_window_ms
        );
        autoclicker.maximum_gap_ms = number(
            "autoclicker.maximum_gap_ms",
            autoclicker.maximum_gap_ms
        );
        autoclicker.maximum_queue_ms = number(
            "autoclicker.maximum_queue_ms",
            autoclicker.maximum_queue_ms
        );
        autoclicker.sample_window_ms = number(
            "autoclicker.sample_window_ms",
            autoclicker.sample_window_ms
        );
        autoclicker.window_intervals = integer(
            "autoclicker.window_intervals",
            autoclicker.window_intervals,
            256
        );
        autoclicker.alert_after = integer(
            "autoclicker.alert_after",
            autoclicker.alert_after,
            100
        );
        (void)AutoClickerCheck(autoclicker);

        // Remaining keys are misspelled or unsupported, not silently ignored.
        if(!values.empty())
            throw std::invalid_argument("Unknown setting: " + values.begin()->first);

        // Each factory creates a separate detector instance for each player session.
        if(fastbreak_enabled){
            setup.factories.push_back([settings]{
                return std::make_unique<FastBreakCheck>(settings);
            });
        }

        if(reach_enabled){
            setup.factories.push_back([reach]{
                return std::make_unique<ReachCheck>(reach);
            });
        }

        if(autoclicker_enabled){
            setup.factories.push_back([autoclicker]{
                return std::make_unique<AutoClickerCheck>(autoclicker);
            });
        }

        return setup;
    }

}
