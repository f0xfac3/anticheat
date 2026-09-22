// Synthetic protocol/model regressions, not recordings of Vape or human players.
#include "anticheat/builtins.hpp"
#include "anticheat/engine.hpp"
#include <cmath>
#include <functional>
#include <iostream>
#include <limits>
#include <map>
#include <stdexcept>

namespace {
    void require(bool value, const char *reason) {
        if (!value)
            throw std::runtime_error(reason);
    }
    struct Lab {
        ac::Engine engine;
        std::uint64_t time = 1000000000, ordinal = 0, packet = 0, batch = 0;
        ac::Vector3 pos{0, 100, 0};
        ac::MovementContext context{"world", "", true, false, false, true, false, false, false, .1, .6, .42};
        std::map<std::string, int> alerts;
        static ac::Engine make(const std::string &config) {
            auto s = ac::builtin_checks("trace=false\nfastbreak.enabled=false\nautoclicker.enabled=false\n" +
                                        config);
            return {s.trace, std::move(s.factories)};
        }
        explicit Lab(const std::string &config = "") : engine(make(config)) {
            send(ac::SessionStart{"test", 47, 10808});
        }
        void send(ac::Payload p) {
            for (const auto &f : engine.process({{1, ++ordinal, time, 0, time / 50000000}, std::move(p)}))
                if (f.level == "suspicious")
                    ++alerts[f.check_id];
        }
        void step(double ms = 50) {
            time += static_cast<std::uint64_t>(std::llround(ms * 1e6));
        }
        void move(ac::Vector3 d = {}, double ms = 50, bool ground = false, bool full = true,
                  bool newBatch = true, double queue = 0) {
            step(ms);
            pos = {pos.x + d.x, pos.y + d.y, pos.z + d.z};
            send(ac::MovementEvent{++packet, newBatch ? ++batch : batch,
                                   time + static_cast<std::uint64_t>(queue * 1e6), pos, 0, 0, full, true,
                                   ground, context});
        }
        int count(const char *id) {
            return alerts[id];
        }
        void flat() {
            context.source_supported = context.destination_supported = context.flat_ground = true;
        }
    };
    void test(const char *name, const std::function<void()> &fn, int &n) {
        fn();
        ++n;
        std::cout << "PASS " << name << '\n';
    }
    void fall(Lab &l, bool fakeGround, bool hover = false) {
        double vy = -.1, fallen = 0;
        for (int i = 0; i < 18; i++) {
            fallen += std::abs(vy);
            l.move({0, hover ? -.04 : vy, 0}, 50, fakeGround && fallen > 2.224);
            vy = (vy - .08) * .98;
        }
    }
    // A small forward simulation of vanilla ground movement: input damping .98,
    // moveFlying normalization, then position, then ground friction.
    void groundRun(Lab &l, double input, double multiplier = 1, int ticks = 60) {
        double vx = 0;
        const double drag = static_cast<double>(.91f) * static_cast<double>(.6f);
        for (int i = 0; i < ticks; i++) {
            double moveFlying = .1 * static_cast<double>(.16277136f) / (drag * drag * drag);
            vx += input * .98 * moveFlying;
            l.move({vx * multiplier, 0, 0}, 50, true);
            vx *= drag;
        }
    }
    void impulseTrial(Lab &l, std::uint64_t token, double scale, bool ack = true, bool additive = false,
                      bool attack = false) {
        l.move();
        l.move();
        l.send(ac::ImpulseEvent{token, {.6, .4, 0}, additive});
        if (ack)
            l.send(ac::ImpulseAckEvent{token});
        double vx = .6 * scale, vy = .4 * scale - (additive ? .08 * .98 : 0);
        for (int i = 0; i < 9; i++) {
            if (attack && i == 0)
                l.send(ac::AttackEvent{});
            l.move({vx, vy, 0});
            vx *= .91;
            vy = (vy - .08) * .98;
        }
    }
    ac::CombatContext target(double yaw = 0) {
        // With yaw zero, ordinary +.1 picking misses (min X .32), while the
        // recovered default +.35 expansion hits (min X -.03), at distance <3.
        ac::CombatContext c{
            "world", "target", "PLAYER", "", 7, {0, 101.62, 0}, {{.42, 100, 1.7}, {1.02, 101.8, 2.3}},
            10,      true};
        c.rotation_available = true;
        c.yaw = yaw;
        c.target_player = true;
        return c;
    }
    void aim(Lab &l, double yaw, bool rotating = false) {
        for (int i = 0; i < 24; i++) {
            l.step();
            auto c = target(rotating && i % 2 ? yaw + 5 : yaw);
            l.send(ac::CombatContextEvent{l.time, c});
        }
        for (int a = 0; a < 3; a++) {
            l.send(ac::AttackEvent{++l.packet, ++l.batch, l.time, target(yaw)});
            for (int i = 0; i < 3; i++) {
                l.step();
                l.send(ac::CombatContextEvent{l.time, target(rotating ? yaw + 5 : yaw)});
            }
        }
    }
} // namespace
int main() {
    int passed = 0;
    try {
        test(
            "Timer accepts 20 Hz including look/ground-only packets",
            [] {
                Lab l;
                for (int i = 0; i < 1000; i++)
                    l.move({}, 50, false, i % 3 == 0);
                require(!l.count("timer.budget.v1"), "normal cadence");
            },
            passed);
        test(
            "Timer catches sustained 1.07, 1.5 and 2.0 multipliers",
            [] {
                for (double s : {1.07, 1.5, 2.0}) {
                    Lab l;
                    for (int i = 0; i < 1400; i++)
                        l.move({}, 50 / s, false, false);
                    require(l.count("timer.budget.v1") > 0, "accelerated cadence missed");
                }
            },
            passed);
        test(
            "Timer tolerates periodic 200 ms coalescing",
            [] {
                Lab l;
                for (int i = 0; i < 1000; i++)
                    l.move({}, i % 4 == 0 ? 200 : 0, false, false, i % 4 == 0);
                require(!l.count("timer.budget.v1"), "coalescing flagged");
            },
            passed);
        test(
            "Timer discards stale snapshots and gaps",
            [] {
                Lab l;
                for (int i = 0; i < 500; i++)
                    l.move({}, 25, false, false, true, 101);
                require(!l.count("timer.budget.v1"), "stale packets counted");
                for (int i = 0; i < 100; i++) {
                    l.move({}, 800);
                    for (int j = 0; j < 15; j++)
                        l.move({}, 25);
                }
                require(!l.count("timer.budget.v1"), "gap credit not reset");
            },
            passed);
        test(
            "Correction and unavailable terrain discard prior timer lead",
            [] {
                Lab l;
                for (int r = 0; r < 15; r++) {
                    for (int i = 0; i < 40; i++)
                        l.move({}, 25);
                    l.send(ac::CorrectionEvent{});
                    l.context.available = false;
                    l.move();
                    l.context.available = true;
                }
                require(!l.count("timer.budget.v1"), "boundary bridged");
            },
            passed);
        test(
            "Vanilla flat running fits Speed envelope",
            [] {
                Lab l;
                l.flat();
                groundRun(l, 1);
                require(!l.count("speed.plain.v1"), "vanilla running flagged");
            },
            passed);
        test(
            "Large staged horizontal override contradicts Speed envelope",
            [] {
                Lab l;
                l.flat();
                groundRun(l, 1, 2.149);
                require(l.count("speed.plain.v1") > 0, "speed missed");
            },
            passed);
        test(
            "Item .2 input fits NoSlow, restored input does not",
            [] {
                Lab normal;
                normal.flat();
                normal.context.using_item = true;
                groundRun(normal, .2);
                require(!normal.count("noslow.item_input.v1"), "vanilla item slowdown flagged");
                Lab cheat;
                cheat.flat();
                cheat.context.using_item = true;
                groundRun(cheat, 1);
                require(cheat.count("noslow.item_input.v1") > 0, "restored input missed");
            },
            passed);
        test(
            "Item-independent movement is not a NoSlow sample",
            [] {
                Lab l;
                l.flat();
                groundRun(l, 1);
                require(!l.count("noslow.item_input.v1"), "no item flagged");
            },
            passed);
        test(
            "Gravity fall fits Fly and honest ground claims",
            [] {
                Lab l;
                fall(l, false);
                require(!l.count("fly.airborne.v1") && !l.count("nofall.ground_claim.v1"),
                        "normal fall flagged");
            },
            passed);
        test(
            "Repeated false ground during descent triggers NoFall",
            [] {
                Lab l;
                fall(l, true);
                require(l.count("nofall.ground_claim.v1") > 0, "false ground missed");
            },
            passed);
        test(
            "Supported ground cannot become a NoFall sample",
            [] {
                Lab l;
                l.flat();
                for (int i = 0; i < 30; i++)
                    l.move({}, 50, true);
                require(!l.count("nofall.ground_claim.v1"), "standing flagged");
            },
            passed);
        test(
            "Airborne vertical override triggers Fly",
            [] {
                Lab l;
                fall(l, false, true);
                require(l.count("fly.airborne.v1") > 0, "hover missed");
            },
            passed);
        test(
            "Collision and repeated read batch suppress physics evidence",
            [] {
                Lab l;
                l.context.clear_path = false;
                fall(l, true, true);
                require(l.alerts.empty(), "collision flagged");
                Lab b;
                for (int i = 0; i < 25; i++)
                    b.move({0, -.05, 0}, 50, true, true, false);
                require(b.alerts.empty(), "one batch counted repeatedly");
            },
            passed);
        test(
            "Unreported positions and invalid coordinates suppress physics",
            [] {
                Lab l;
                for (int i = 0; i < 30; i++)
                    l.move({0, -.04, 0}, 50, true, false);
                require(!l.count("fly.airborne.v1") && !l.count("nofall.ground_claim.v1"),
                        "omitted position inferred");
                l.pos.x = std::numeric_limits<double>::quiet_NaN();
                fall(l, true, true);
                require(!l.count("fly.airborne.v1"), "NaN flagged");
            },
            passed);
        test(
            "Impulse grace suppresses ordinary movement checks",
            [] {
                Lab l;
                l.send(ac::ImpulseEvent{1, {.4, .4, 0}, false});
                fall(l, true, true);
                require(!l.count("fly.airborne.v1") && !l.count("nofall.ground_claim.v1"),
                        "impulse grace missing");
            },
            passed);
        test(
            "Replacement and additive legitimate impulses survive",
            [] {
                for (bool add : {false, true}) {
                    Lab l;
                    for (int i = 1; i <= 4; i++)
                        impulseTrial(l, static_cast<std::uint64_t>(i), 1, true, add);
                    require(!l.count("velocity.response.v1"), "legitimate impulse flagged");
                }
            },
            passed);
        test(
            "Acknowledged 30 percent impulse responses accumulate",
            [] {
                Lab l;
                for (int i = 1; i <= 3; i++)
                    impulseTrial(l, static_cast<std::uint64_t>(i), .3);
                require(l.count("velocity.response.v1") == 1, "reduced impulse missed");
            },
            passed);
        test(
            "Unacknowledged impulses never become Velocity evidence",
            [] {
                Lab l;
                for (int i = 1; i <= 5; i++)
                    impulseTrial(l, static_cast<std::uint64_t>(i), .3, false);
                require(!l.count("velocity.response.v1"), "unacknowledged impulse flagged");
            },
            passed);
        test(
            "Attack exempts local slowdown during impulse",
            [] {
                Lab l;
                for (int i = 1; i <= 5; i++)
                    impulseTrial(l, static_cast<std::uint64_t>(i), .3, true, false, true);
                require(!l.count("velocity.response.v1"), "attack reduction flagged");
            },
            passed);
        test(
            "Stable miss yields HitBoxes evidence after future snapshots",
            [] {
                Lab l;
                aim(l, 0);
                require(l.count("hitboxes.stable_ray.v1") == 1, "stable miss not detected");
            },
            passed);
        test(
            "Valid ray and changing aim do not yield HitBoxes evidence",
            [] {
                Lab l;
                aim(l, -20);
                require(!l.count("hitboxes.stable_ray.v1"), "valid ray flagged");
                Lab r;
                aim(r, 0, true);
                require(!r.count("hitboxes.stable_ray.v1"), "changing aim flagged");
            },
            passed);
        test(
            "KeepSprint is disabled by default",
            [] {
                auto s = ac::builtin_checks("");
                for (const auto &f : s.factories)
                    require(f()->id() != "keepsprint.attack_retention.v1", "unsafe default");
            },
            passed);
        test(
            "KeepSprint conditional lab branch separates .6 from .95 retention",
            [] {
                for (double retain : {.6, .95}) {
                    Lab l("keepsprint.enabled=true");
                    l.flat();
                    l.context.sprinting = true;
                    for (int trial = 0; trial < 3; trial++) {
                        groundRun(l, 1, 1, 20);
                        auto c = target();
                        c.attacker_sprinting = true;
                        l.send(ac::AttackEvent{++l.packet, ++l.batch, l.time, c});
                        const double terminal = .098 / (1 - .546);
                        l.move({terminal * .546 * retain + .098, 0, 0}, 50, true);
                    }
                    require((l.count("keepsprint.attack_retention.v1") > 0) == (retain > .6),
                            "conditional retention model");
                }
            },
            passed);
        test(
            "Configuration rejects unsafe values even for disabled checks",
            [] {
                for (const char *setting :
                     {"timer.credit_ms=0", "velocity.epsilon=nan", "hitboxes.angle_grace=0",
                      "nofall.alert_after=1", "keepsprint.alert_after=2.5"}) {
                    bool rejected = false;
                    try {
                        (void)ac::builtin_checks(setting);
                    } catch (const std::invalid_argument &) {
                        rejected = true;
                    }
                    require(rejected, "invalid config accepted");
                }
            },
            passed);
        std::cout << passed << " movement/research tests passed\n";
        return 0;
    } catch (const std::exception &e) {
        std::cerr << "FAIL after " << passed << ": " << e.what() << '\n';
        return 1;
    }
}
