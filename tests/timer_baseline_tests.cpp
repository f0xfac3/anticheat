// Synthetic regression fixtures. These are not player recordings or calibration data.
#include "anticheat/builtins.hpp"
#include "anticheat/engine.hpp"
#include "timer_baseline.hpp"
#include <iostream>
#include <stdexcept>

static void require(bool ok, const char *message) {
    if (!ok)
        throw std::runtime_error(message);
}
struct Lab {
    ac::Engine engine;
    std::uint64_t now = 1000000000, ordinal = 0, packet = 0;
    std::vector<ac::Finding> assessments;
    explicit Lab(std::size_t count)
        : engine(false, {[count] {
                     return std::make_unique<ac::TimerBaselineCheck>(ac::TimerBaseline{
                         std::string(64, 'a'), std::vector<double>(count, 20), .001});
                 }}) {
        send(ac::SessionStart{"00000000-0000-0000-0000-000000000001", 47, 10808});
    }
    void send(ac::Payload event) {
        for (auto &f : engine.process({{1, ++ordinal, now, 0, now / 50000000}, std::move(event)}))
            assessments.push_back(f);
    }
    void move(std::uint64_t interval, bool available = true) {
        now += interval;
        ac::MovementEvent event;
        event.packet_sequence = ++packet;
        event.read_batch = packet;
        event.sampled_ns = now;
        event.context.world_uuid = "world";
        event.context.available = available;
        send(event);
    }
    void run(double multiplier, bool available = true) {
        auto end = now + 176000000000ULL;
        while (now < end)
            move(static_cast<std::uint64_t>(50000000 / multiplier), available);
    }
    std::string field(const char *key) {
        for (auto &p : assessments.back().evidence)
            if (p.first == key)
                return p.second;
        throw std::runtime_error("missing evidence");
    }
};
int main() {
    try {
        Lab small(9);
        small.run(1.07);
        require(small.assessments.size() == 1, "small baseline assessment missing");
        require(small.field("eligible") == "false", "nine seeds must not ban");
        require(std::stod(small.field("tail_p")) == .1, "tail rank wrong");
        Lab adequate(1999);
        adequate.run(1.07);
        require(adequate.field("eligible") == "true", "sustained candidate missed");
        require(std::stod(adequate.field("alpha_spent")) == .0005, "small threshold rounded");
        adequate.send(ac::ResetEvent{"teleport"});
        adequate.run(1.07);
        require(adequate.field("episode") == "2" && adequate.field("eligible") == "false",
                "reset replenished alpha budget");
        Lab normal(1999);
        normal.run(1);
        require(normal.field("eligible") == "false", "normal cadence banned");
        Lab stale(1999);
        stale.run(2, false);
        require(stale.assessments.empty(), "unavailable context counted");
        Lab burst(1999);
        for (int i = 0; i < 3600; i++)
            burst.move(i % 4 == 0 ? 200000000 : 0);
        require(burst.field("eligible") == "false", "ordinary coalescing banned");
        Lab interrupted(1999);
        for (int i = 0; i < 2000; i++)
            interrupted.move(25000000);
        interrupted.send(ac::CorrectionEvent{});
        for (int i = 0; i < 2000; i++)
            interrupted.move(25000000);
        require(interrupted.assessments.empty(), "partial episodes joined across correction");
        auto setup = ac::builtin_checks(
            "timer.baseline.enabled=true\ntimer.baseline.model=" + std::string(64, 'a') +
            "\ntimer.baseline.reference=20,20,20,20,20\ntimer.baseline.alpha=0.001");
        require(!setup.factories.empty(), "configuration parser failed");
        std::cout << "PASS Timer baseline: abstention, tail precision, eligibility, alpha "
                     "spending, resets, normal/coalesced traffic, configuration\n";
        return 0;
    } catch (const std::exception &error) {
        std::cerr << error.what() << '\n';
        return 1;
    }
}
