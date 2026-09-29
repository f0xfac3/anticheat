#include "timer_baseline.hpp"
#include "combat_common.hpp"
#include <algorithm>
#include <cmath>
#include <numeric>
#include <stdexcept>

namespace ac {
namespace {
constexpr std::uint64_t window_ns = 5000000000ULL;
constexpr std::uint64_t gap_ns = 750000000ULL;
constexpr std::uint64_t queue_ns = 100000000ULL;
std::string precise(double value) {
    std::ostringstream out;
    out.imbue(std::locale::classic());
    out << std::setprecision(17) << value;
    return out.str();
}
} // namespace

TimerBaselineCheck::TimerBaselineCheck(TimerBaseline baseline) : baseline_(std::move(baseline)) {
    if (baseline_.model.size() != 64 ||
        baseline_.model.find_first_not_of("0123456789abcdef") != std::string::npos ||
        baseline_.reference.size() < 5 || baseline_.reference.size() > 4096 ||
        !std::isfinite(baseline_.alpha) || baseline_.alpha <= 0 || baseline_.alpha > .01)
        throw std::invalid_argument("Invalid Timer baseline identity/reference/alpha");
    for (double value : baseline_.reference)
        if (!std::isfinite(value) || value < 0 || value > 1000)
            throw std::invalid_argument("Invalid Timer reference score");
    std::sort(baseline_.reference.begin(), baseline_.reference.end());
}

void TimerBaselineCheck::reset(std::string_view) {
    start_.reset();
    previous_.reset();
    packet_ = 0;
    counts_.fill(0);
    // Discontinuities must not replenish the session's alpha spending budget.
}

void TimerBaselineCheck::registerHandlers(CheckManager &manager) {
    manager.on<MovementEvent>([this](const auto &e, auto &c) { movement(e, c); });
    manager.on<TeleportEvent>([this](const auto &, auto &) { reset("teleport"); });
    manager.on<CorrectionEvent>([this](const auto &, auto &) { reset("correction"); });
}

void TimerBaselineCheck::movement(const MovementEvent &e, CheckContext &c) {
    const auto now = c.event.observed_ns;
    const bool valid = combat_model(c.player.identity) && e.context.available &&
                       !e.context.world_uuid.empty() && e.sampled_ns >= now &&
                       e.sampled_ns - now <= queue_ns;
    const bool ordered = !packet_ || e.packet_sequence > packet_;
    const bool timely = !previous_ || (now >= *previous_ && now - *previous_ < gap_ns);
    if (!valid || !ordered || !timely) {
        reset("unreliable");
        if (!valid || !ordered)
            return;
    }
    if (!start_)
        start_ = now;
    previous_ = now;
    packet_ = e.packet_sequence;
    const auto elapsed = now - *start_;
    if (elapsed >= 35 * window_ns) {
        assess(c);
        start_ = now;
        counts_.fill(0);
    } else if (elapsed >= window_ns) {
        auto &count = counts_[static_cast<std::size_t>(elapsed / window_ns - 1)];
        if (++count > 20000)
            reset("rate_limit");
    }
}

void TimerBaselineCheck::assess(CheckContext &c) {
    ++episode_;
    double score = 0;
    for (std::size_t i = 0; i + 2 < counts_.size(); ++i)
        score = std::max(
            score, static_cast<double>(std::min({counts_[i], counts_[i + 1], counts_[i + 2]})) / 5);
    auto greater = baseline_.reference.end() -
                   std::lower_bound(baseline_.reference.begin(), baseline_.reference.end(), score);
    const double n = static_cast<double>(baseline_.reference.size());
    const double tail = (1.0 + static_cast<double>(greater)) / (n + 1);
    const double k = static_cast<double>(episode_);
    const double spent = baseline_.alpha / (k * (k + 1));
    const auto packets = std::accumulate(counts_.begin(), counts_.end(), std::uint64_t{});
    const double excess = static_cast<double>(packets) * 50 - 170000;
    const bool eligible = tail <= spent && score >= 20.5 && excess >= 1000;
    const std::string reason = eligible              ? "sustained_timer_excess"
                               : 1 / (n + 1) > spent ? "insufficient_reference"
                                                     : "within_policy";
    c.emit("assessment", std::string(id()), reason,
           {{"model", baseline_.model},
            {"algorithm", "timer-episode-v1"},
            {"score_pps", precise(score)},
            {"tail_p", precise(tail)},
            {"alpha_spent", precise(spent)},
            {"reference_count", std::to_string(baseline_.reference.size())},
            {"episode", std::to_string(episode_)},
            {"packets", std::to_string(packets)},
            {"excess_ms", combat_number(excess)},
            {"eligible", eligible ? "true" : "false"}});
}
} // namespace ac
