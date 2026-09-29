#pragma once
#include "anticheat/check.hpp"
#include <array>
#include <optional>
#include <vector>

namespace ac {
struct TimerBaseline {
    std::string model;
    std::vector<double> reference;
    double alpha = .001;
};

// One bounded episode; no file IO, training, JNI or Bukkit dependencies.
class TimerBaselineCheck final : public Check {
    TimerBaseline baseline_;
    std::optional<std::uint64_t> start_, previous_;
    std::uint64_t packet_{}, episode_{};
    std::array<std::uint32_t, 34> counts_{};
    void movement(const MovementEvent &, CheckContext &);
    void assess(CheckContext &);

  public:
    explicit TimerBaselineCheck(TimerBaseline baseline);
    std::string_view id() const noexcept override { return "timer.baseline.v1"; }
    void registerHandlers(CheckManager &) override;
    void reset(std::string_view) override;
};
} // namespace ac
