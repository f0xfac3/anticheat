/**
 * Combat detector regression tests use generated observations, not labeled human gameplay.
 */

#include "anticheat/builtins.hpp"
#include "anticheat/engine.hpp"
#include "reach.hpp"
#include "autoclicker.hpp"
#include "wire.hpp"
#include <functional>
#include <iostream>
#include <limits>
#include <random>
#include <stdexcept>

namespace{

    void require(bool condition, const char* message){
        if(!condition)
            throw std::runtime_error(message);
    }

    std::size_t alerts(const std::vector<ac::Finding>& findings, std::string_view check){
        std::size_t count = 0;
        for(const auto& finding : findings){
            if(finding.level == "suspicious" && finding.check_id == check)
                ++count;
        }
        return count;
    }

    bool message(const std::vector<ac::Finding>& findings, std::string_view text){
        for(const auto& finding : findings){
            if(finding.message == text)
                return true;
            for(const auto& field : finding.evidence){
                if(field.first == "reason" && field.second == text)
                    return true;
            }
        }
        return false;
    }

    struct Lab{
        ac::Engine engine;
        std::uint64_t time = 1000000000;
        std::uint64_t ordinal = 0;
        std::uint64_t packet = 0;
        std::uint64_t batch = 0;

        static ac::Engine make(const std::string& config){
            auto setup = ac::builtin_checks("fastbreak.enabled=false\n" + config);
            return ac::Engine(setup.trace, std::move(setup.factories));
        }

        explicit Lab(const std::string& config = "") : engine(make(config)){
            send(ac::SessionStart{"player", 47, 10808});
        }

        std::vector<ac::Finding> send(ac::Payload payload){
            return engine.process({{1, ++ordinal, time, 0, time / 50000000}, std::move(payload)});
        }

        static ac::CombatContext context(double picking_distance = 3.4){
            // The normal client expands the raw target box by 0.1 for picking.
            return {"world", "target", "ZOMBIE", "", 7, {0, 65.62, 0},
                {{picking_distance + 0.1, 64, -0.3}, {picking_distance + 0.7, 65.8, 0.3}}, 10, true};
        }

        void advance(double ms){
            time += static_cast<std::uint64_t>(std::llround(ms * 1000000));
        }

        void frame(ac::CombatContext context){
            send(ac::CombatContextEvent{time, std::move(context)});
        }

        void warm(ac::CombatContext context = Lab::context()){
            for(int i = 0; i <= 16; ++i){
                if(i)
                    advance(50);
                frame(context);
            }
        }

        std::vector<ac::Finding> attack(ac::CombatContext context = Lab::context(), double after = 80){
            advance(after);
            return send(ac::AttackEvent{++packet, ++batch, time, std::move(context)});
        }

        std::size_t cadence(const std::vector<double>& intervals){
            std::size_t total = 0;
            attack(context(2), 0);
            for(double interval : intervals)
                total += alerts(attack(context(2), interval), "autoclicker.cadence.v1");
            return total;
        }
    };

    void test(const char* name, const std::function<void()>& body, int& passed){
        body();
        std::cout << "PASS " << name << '\n';
        ++passed;
    }

}

int main(){
    int passed = 0;
    try{
        test("stationary extended reach requires three samples", []{
            Lab lab("autoclicker.enabled=false");
            lab.warm();
            require(alerts(lab.attack(), "reach.stationary.v1") == 0, "first attack alerted");
            require(alerts(lab.attack(), "reach.stationary.v1") == 0, "second attack alerted");
            auto result = lab.attack();
            require(alerts(result, "reach.stationary.v1") == 1, "third attack missing");
            require(ac::to_json(result.back()).find("NOT_RECONSTRUCTED") != std::string::npos, "missing limitation");
        }, passed);
        test("3.0 reach is inside the modeled bound", []{
            Lab lab("autoclicker.enabled=false\nreach.alert_after=1");
            lab.warm(Lab::context(3));
            require(alerts(lab.attack(Lab::context(3)), "reach.stationary.v1") == 0, "normal reach flagged");
        }, passed);
        test("3.2, 3.4, and 4.0 stationary geometry can alert", []{
            for(double distance : {3.2, 3.4, 4.0}){
                Lab lab("autoclicker.enabled=false\nreach.alert_after=1");
                lab.warm(Lab::context(distance));
                require(alerts(lab.attack(Lab::context(distance)), "reach.stationary.v1") == 1, "extended reach missed");
            }
        }, passed);
        test("picking border and encoding allowance do not become reach", []{
            Lab lab("autoclicker.enabled=false\nreach.alert_after=1");
            lab.warm(Lab::context(3.08));
            require(alerts(lab.attack(Lab::context(3.08)), "reach.stationary.v1") == 0, "padding lost");
        }, passed);
        test("new target must warm geometry history", []{
            Lab lab;
            require(message(lab.attack(), "history_warming"), "no warmup");
        }, passed);
        test("moving target is not evaluated as stationary", []{
            Lab lab("reach.alert_after=1");
            lab.warm();
            auto context = Lab::context();
            context.target_box.minimum.x += 0.2;
            context.target_box.maximum.x += 0.2;
            require(message(lab.attack(context), "moving_player_or_target"), "motion ignored");
        }, passed);
        test("moving attacker is not evaluated as stationary", []{
            Lab lab("reach.alert_after=1");
            lab.warm();
            auto context = Lab::context();
            context.eye.z += 0.2;
            require(message(lab.attack(context), "moving_player_or_target"), "attacker motion ignored");
        }, passed);
        test("high ping excludes stationary comparison", []{
            Lab lab;
            lab.warm();
            auto context = Lab::context();
            context.ping_ms = 300;
            require(message(lab.attack(context), "unsupported_snapshot_or_latency"), "ping ignored");
        }, passed);
        test("negative ping is unknown, not zero", []{
            Lab lab;
            lab.warm();
            auto context = Lab::context();
            context.ping_ms = -1;
            require(alerts(lab.attack(context), "reach.stationary.v1") == 0, "unknown ping flagged");
        }, passed);
        test("unavailable target abstains", []{
            Lab lab;
            lab.warm();
            auto context = Lab::context();
            context.available = false;
            context.unavailable_reason = "missing_target";
            require(message(lab.attack(context), "missing_target"), "missing target ignored");
        }, passed);
        test("nonfinite geometry abstains", []{
            Lab lab("reach.alert_after=1");
            lab.warm();
            auto context = Lab::context();
            context.eye.x = std::numeric_limits<double>::quiet_NaN();
            require(alerts(lab.attack(context), "reach.stationary.v1") == 0, "nan accepted");
        }, passed);
        test("inverted bounds abstain", []{
            Lab lab("reach.alert_after=1");
            lab.warm();
            auto context = Lab::context();
            std::swap(context.target_box.minimum.x, context.target_box.maximum.x);
            require(alerts(lab.attack(context), "reach.stationary.v1") == 0, "inverted bounds accepted");
        }, passed);
        test("excessive geometry abstains", []{
            Lab lab("reach.alert_after=1");
            lab.warm();
            auto context = Lab::context();
            context.eye.x = 1e100;
            require(alerts(lab.attack(context), "reach.stationary.v1") == 0, "unbounded coordinate accepted");
        }, passed);
        test("delayed reach snapshot drops geometry history", []{
            Lab lab;
            lab.warm();
            lab.advance(80);
            auto records = lab.send(ac::AttackEvent{++lab.packet, ++lab.batch, lab.time + 400000000, Lab::context()});
            require(message(records, "delayed_snapshot"), "delayed sample ignored");
            require(message(lab.attack(), "history_warming"), "stale history retained");
        }, passed);
        test("reversed sample time abstains", []{
            Lab lab;
            lab.warm();
            auto records = lab.send(ac::AttackEvent{++lab.packet, ++lab.batch, lab.time - 1, Lab::context()});
            require(message(records, "delayed_snapshot"), "reversed timestamp ignored");
        }, passed);
        test("large frame gap restarts warmup", []{
            Lab lab;
            lab.warm();
            require(message(lab.attack(Lab::context(), 500), "history_warming"), "gap retained");
        }, passed);
        test("duplicate packet cannot accumulate reach evidence", []{
            Lab lab("reach.alert_after=2");
            lab.warm();
            lab.attack();
            auto records = lab.send(ac::AttackEvent{lab.packet, ++lab.batch, lab.time, Lab::context()});
            require(message(records, "duplicate_or_out_of_order_packet"), "duplicate packet counted");
        }, passed);
        test("same read cycle does not count twice", []{
            Lab lab("reach.alert_after=2");
            lab.warm();
            lab.attack();
            lab.advance(1);
            auto records = lab.send(ac::AttackEvent{++lab.packet, lab.batch, lab.time, Lab::context()});
            require(message(records, "same_or_invalid_read_batch"), "batch counted twice");
        }, passed);
        test("changed world requires new history", []{
            Lab lab;
            lab.warm();
            auto context = Lab::context();
            context.world_uuid = "other";
            require(message(lab.attack(context), "history_warming"), "world mixed");
        }, passed);
        test("entity ID reuse cannot inherit UUID history", []{
            Lab lab;
            lab.warm();
            auto context = Lab::context();
            context.target_uuid = "different_entity";
            require(message(lab.attack(context), "history_warming"), "entity reuse mixed");
        }, passed);
        test("teleport clears geometry but preserves completed reach samples", []{
            Lab lab("autoclicker.enabled=false\nreach.alert_after=2");
            lab.warm();
            lab.attack();
            lab.send(ac::TeleportEvent{"ENDER_PEARL", "world", "world", {}, {}});
            require(message(lab.attack(), "history_warming"), "teleport history retained");
            lab.warm();
            require(alerts(lab.attack(), "reach.stationary.v1") == 1, "completed sample erased");
        }, passed);
        test("actual observation loss clears reach evidence", []{
            Lab lab("autoclicker.enabled=false\nreach.alert_after=2");
            lab.warm();
            lab.attack();
            lab.send(ac::ResetEvent{"observation_discontinuity"});
            lab.warm();
            require(alerts(lab.attack(), "reach.stationary.v1") == 0, "loss retained evidence");
        }, passed);
        test("reach sample window expires", []{
            Lab lab("autoclicker.enabled=false\nreach.alert_after=2\nreach.sample_window_ms=1000");
            lab.warm();
            lab.attack();
            lab.advance(1500);
            lab.warm();
            require(alerts(lab.attack(), "reach.stationary.v1") == 0, "reach window never expired");
        }, passed);
        test("regular cadence alerts only after three independent windows", []{
            Lab lab("reach.enabled=false");
            require(lab.cadence(std::vector<double>(120, 75.5)) == 1, "regular cadence missed");
        }, passed);
        test("one regular window is only a trace", []{
            Lab lab("reach.enabled=false");
            require(lab.cadence(std::vector<double>(40, 75.5)) == 0, "one window alerted");
        }, passed);
        test("overlapping windows cannot multiply evidence", []{
            Lab lab("reach.enabled=false");
            require(lab.cadence(std::vector<double>(60, 75.5)) == 0, "overlap counted");
        }, passed);
        test("variable timing is not classified by CPS alone", []{
            Lab lab("reach.enabled=false");
            std::vector<double> intervals;
            for(int i = 0; i < 300; ++i)
                intervals.push_back(i % 2 ? 55 : 105);
            require(lab.cadence(intervals) == 0, "variable timing flagged");
        }, passed);
        test("randomized 10-15 CPS is not guaranteed detectable", []{
            Lab lab("reach.enabled=false");
            std::mt19937 random(17);
            std::uniform_real_distribution<double> cps(10, 15);
            std::vector<double> intervals;
            for(int i = 0; i < 400; ++i)
                intervals.push_back(1000.0 / cps(random));
            require(lab.cadence(intervals) == 0, "broad random cadence flagged");
        }, passed);
        test("tick-quantized 10 and 20 CPS abstain", []{
            for(double interval : {50.0, 100.0}){
                Lab lab("reach.enabled=false");
                require(lab.cadence(std::vector<double>(200, interval)) == 0, "tick quantization flagged");
            }
        }, passed);
        test("slow regular attacks do not satisfy minimum rate", []{
            Lab lab("reach.enabled=false");
            require(lab.cadence(std::vector<double>(160, 190)) == 0, "slow cadence flagged");
        }, passed);
        test("short observation windows are not sufficient", []{
            Lab lab("reach.enabled=false");
            require(lab.cadence(std::vector<double>(200, 15.5)) == 0, "short window flagged");
        }, passed);
        test("same-batch attacks cannot become low-variance windows", []{
            Lab lab("reach.enabled=false\nautoclicker.alert_after=1");
            for(int i = 0; i < 160; ++i){
                lab.advance(75.5);
                require(alerts(lab.send(ac::AttackEvent{++lab.packet, 1, lab.time, Lab::context(2)}), "autoclicker.cadence.v1") == 0, "batch promoted");
            }
        }, passed);
        test("long pause splits cadence window", []{
            Lab lab("reach.enabled=false\nautoclicker.alert_after=1");
            lab.cadence(std::vector<double>(25, 75.5));
            lab.advance(1000);
            require(lab.cadence(std::vector<double>(25, 75.5)) == 0, "pause ignored");
        }, passed);
        test("unavailable combat state cannot feed cadence", []{
            Lab lab("reach.enabled=false\nautoclicker.alert_after=1");
            auto context = Lab::context(2);
            context.available = false;
            for(int i = 0; i < 150; ++i)
                require(alerts(lab.attack(context, 75.5), "autoclicker.cadence.v1") == 0, "unavailable state counted");
        }, passed);
        test("delayed attacks cannot feed cadence", []{
            Lab lab("reach.enabled=false\nautoclicker.alert_after=1");
            for(int i = 0; i < 150; ++i){
                lab.advance(75.5);
                require(alerts(lab.send(ac::AttackEvent{++lab.packet, ++lab.batch, lab.time + 400000000, Lab::context(2)}), "autoclicker.cadence.v1") == 0, "queue delay counted");
            }
        }, passed);
        test("teleport does not join partial cadence windows", []{
            Lab lab("reach.enabled=false\nautoclicker.alert_after=1");
            lab.cadence(std::vector<double>(25, 75.5));
            lab.send(ac::TeleportEvent{"PLUGIN", "world", "world", {}, {}});
            require(lab.cadence(std::vector<double>(25, 75.5)) == 0, "teleport joined intervals");
        }, passed);
        test("completed cadence evidence survives teleport", []{
            Lab lab("reach.enabled=false\nautoclicker.alert_after=2");
            lab.cadence(std::vector<double>(40, 75.5));
            lab.send(ac::TeleportEvent{"ENDER_PEARL", "world", "world", {}, {}});
            require(lab.cadence(std::vector<double>(40, 75.5)) == 1, "teleport erased evidence");
        }, passed);
        test("reset clears cadence evidence", []{
            Lab lab("reach.enabled=false\nautoclicker.alert_after=2");
            lab.cadence(std::vector<double>(40, 75.5));
            lab.send(ac::ResetEvent{"loss"});
            require(lab.cadence(std::vector<double>(40, 75.5)) == 0, "loss retained cadence evidence");
        }, passed);
        test("cadence sample windows are anchored, not renewed forever", []{
            ac::CombatSamples samples;
            samples.add({1, 1, 0, 0, 0}, 1000);
            samples.add({1, 2, 900000000, 0, 0}, 1000);
            require(samples.add({1, 3, 1800000000, 0, 0}, 1000) == 1, "window drifted");
        }, passed);
        test("swings alone are not attacks or physical clicks", []{
            Lab lab("autoclicker.alert_after=1");
            for(int i = 0; i < 200; ++i){
                lab.advance(70);
                require(lab.send(ac::SwingEvent{++lab.packet, ++lab.batch}).empty(), "swing counted as attack");
            }
        }, passed);
        test("disabled detectors leave collection and sessions operational", []{
            Lab lab("reach.enabled=false\nautoclicker.enabled=false");
            lab.warm();
            require(lab.attack().empty(), "disabled detector emitted");
            require(lab.engine.session_count() == 1, "engine disabled");
        }, passed);
        test("unknown and invalid new settings rejected", []{
            for(const char* config : {
                "reach.maximum_distance=nan", "reach.box_padding=-1", "reach.alert_after=0",
                "reach.history_ms=1", "autoclicker.window_intervals=2", "autoclicker.window_intervals=40.5",
                "autoclicker.maximum_cv=2", "autoclicker.enabled=yes", "reach.typo=1"
            }){
                bool threw = false;
                try{
                    ac::builtin_checks(config);
                }catch(const std::invalid_argument&){
                    threw = true;
                }
                require(threw, "invalid settings accepted");
            }
        }, passed);
        test("FastBreak completed evidence survives a typed teleport", []{
            auto setup = ac::builtin_checks(
                "reach.enabled=false\nautoclicker.enabled=false\nfastbreak.alert_after=2"
            );
            ac::Engine engine(setup.trace, std::move(setup.factories));
            std::uint64_t ordinal = 0;
            std::uint64_t packet = 0;
            std::uint64_t time = 1000000000;
            auto send = [&](ac::Payload payload){
                return engine.process({{1, ++ordinal, time, 0, 0}, std::move(payload)});
            };
            auto dig = [&](ac::DigAction action){
                return ac::DigEvent{
                    ++packet, packet, time, action, 1, {0, 64, 0},
                    {"world", "stone|hand", "STONE", "AIR", "", 0.02, true}
                };
            };
            send(ac::SessionStart{"player", 47, 10808});
            send(dig(ac::DigAction::start));
            time += 100000000;
            require(alerts(send(dig(ac::DigAction::finish)), "fastbreak.request.v1") == 0, "premature FastBreak alert");
            send(dig(ac::DigAction::start));
            send(ac::TeleportEvent{"ENDER_PEARL", "world", "world", {}, {}});
            time += 100000000;
            require(alerts(send(dig(ac::DigAction::finish)), "fastbreak.request.v1") == 0, "teleport retained active attempt");
            send(dig(ac::DigAction::start));
            time += 100000000;
            require(alerts(send(dig(ac::DigAction::finish)), "fastbreak.request.v1") == 1, "teleport erased completed evidence");
        }, passed);
        test("schema two random malformed inputs are bounded", []{
            std::mt19937 random(47);
            for(int i = 0; i < 10000; ++i){
                std::vector<std::uint8_t> bytes(48 + random() % 1000);
                for(auto& byte : bytes)
                    byte = static_cast<std::uint8_t>(random());
                bytes[0] = 'F'; bytes[1] = 'X'; bytes[2] = 'A'; bytes[3] = 'C';
                bytes[4] = 2; bytes[5] = 0;
                bytes[6] = static_cast<std::uint8_t>(7 + i % 4); bytes[7] = 0;
                try{
                    (void)ac::decode_event(bytes.data(), bytes.size());
                }catch(const std::invalid_argument&){
                }
            }
        }, passed);
        std::cout << passed << " combat tests passed\n";
        return 0;
    }catch(const std::exception& error){
        std::cerr << "FAIL after " << passed << ": " << error.what() << '\n';
        return 1;
    }
}
