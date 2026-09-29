/**
 * Replays length-prefixed bridge observations through the same engine used by JNI.
 * Usage: anticheat_replay capture.events [engine.conf]
 */

#include "anticheat/builtins.hpp"
#include "anticheat/engine.hpp"
#include "wire.hpp"
#include <array>
#include <fstream>
#include <iostream>
#include <stdexcept>
#include <vector>

int main(int argc, char** argv){
    try{
        if(argc < 2 || argc > 3)
            throw std::invalid_argument("Usage: anticheat_replay capture.events [engine.conf]");

        std::string configuration;

        if(argc == 3){
            std::ifstream config(argv[2], std::ios::binary);
            std::array<char, 131073> bytes{};

            if(!config)
                throw std::runtime_error("Cannot open configuration");

            config.read(bytes.data(), static_cast<std::streamsize>(bytes.size()));
            configuration.assign(bytes.data(), static_cast<std::size_t>(config.gcount()));
        }

        auto setup = ac::builtin_checks(configuration);
        ac::Engine engine(setup.trace, std::move(setup.factories));
        std::ifstream input(argv[1], std::ios::binary);

        if(!input)
            throw std::runtime_error("Cannot open observation stream");

        std::size_t count = 0;
        std::array<std::uint8_t, 4> length{};

        while(true){
            input.read(reinterpret_cast<char*>(length.data()), 4);

            if(input.gcount() == 0 && input.eof())
                break;

            if(input.gcount() != 4)
                throw std::runtime_error("Truncated observation length");

            std::uint32_t size = 0;

            for(unsigned i = 0; i < 4; ++i)
                size |= std::uint32_t(length[i]) << (8 * i);

            if(size < 48 || size > 8192)
                throw std::runtime_error("Invalid observation length");

            std::vector<std::uint8_t> bytes(size);
            input.read(reinterpret_cast<char*>(bytes.data()), size);

            if(input.gcount() != static_cast<std::streamsize>(size))
                throw std::runtime_error("Truncated observation payload");

            for(const auto& finding : engine.process(ac::decode_event(bytes.data(), bytes.size()))){
                std::cout << ac::to_json(finding) << '\n';
            }

            ++count;
        }

        std::cerr << count << " observations replayed\n";
        return 0;
    }catch(const std::exception& error){
        std::cerr << error.what() << '\n';
        return 1;
    }
}
