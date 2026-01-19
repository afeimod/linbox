#include "gpu.h"
#include "file_utils.h"
#include "hud_elements.h"
#include "overlay_params.h"
#include <fstream>
#include <algorithm>
#include <iostream>
#include <thread>
#include <chrono>

namespace fs = ghc::filesystem;

GPUS::GPUS(const overlay_params* early_params) {
    std::set<std::string> gpu_entries;
    auto params = early_params ? early_params : get_params().get();

    if (fs::exists("/sys/class/drm")) {
        try {
            for (const auto& entry : fs::directory_iterator("/sys/class/drm")) {
                if (!entry.is_directory()) continue;
                std::string node_name = entry.path().filename().string();
                if (node_name.find("renderD") == 0 && node_name.length() > 7) {
                    std::string render_number = node_name.substr(7);
                    if (std::all_of(render_number.begin(), render_number.end(), ::isdigit)) {
                        gpu_entries.insert(node_name);
                    }
                }
            }
        } catch (...) {
            SPDLOG_WARN("Skipping /sys/class/drm scan due to permission error.");
        }
    }

    uint8_t idx = 0, total_active = 0;

    for (const auto& node_name : gpu_entries) {
        const std::string driver = get_driver(node_name);
        if (driver.empty()) continue;

        {
            const std::string* d = std::find(std::begin(supported_drivers), std::end(supported_drivers), driver);
            if (d == std::end(supported_drivers)) continue;
        }

        std::string path = "/sys/class/drm/" + node_name;
        std::string device_address = get_pci_device_address(path);
        const char* pci_dev = device_address.c_str();
        uint32_t vendor_id = 0, device_id = 0;

        if (!device_address.empty()) {
            try { vendor_id = std::stoul(read_line("/sys/bus/pci/devices/" + device_address + "/vendor"), nullptr, 16); } catch(...) {}
            try { device_id = std::stoul(read_line("/sys/bus/pci/devices/" + device_address + "/device"), nullptr, 16); } catch (...) {}
        }

        std::shared_ptr<GPU> ptr = std::make_shared<GPU>(node_name, vendor_id, device_id, pci_dev, driver);
        if (params->gpu_list.size() == 1 && params->gpu_list[0] == idx++) ptr->is_active = true;
        if (!params->pci_dev.empty() && pci_dev == params->pci_dev) ptr->is_active = true;
        available_gpus.emplace_back(ptr);
        if (ptr->is_active) total_active++;
    }

    if (fs::exists("/sys/class/kgsl/kgsl-3d0/gpubusy")) {
        bool already_added = false;
        for (auto& g : available_gpus) {
            if (g->driver == "adreno" || g->driver == "freedreno") already_added = true;
        }

        if (!already_added) {
            auto adreno = std::make_shared<GPU>("Adreno", 0x5143, 0, "0000:00:00.0", "adreno");
            
            if (total_active == 0) {
                adreno->is_active = true;
                total_active++;
            }
            available_gpus.emplace_back(adreno);

            std::thread([adreno](){
                while(true) {
                    std::ifstream stream("/sys/class/kgsl/kgsl-3d0/gpubusy");
                    if (stream.is_open()) {
                        std::string line;
                        if (std::getline(stream, line)) {
                            long long used = 0, total = 0;
                            if (sscanf(line.c_str(), "%lld %lld", &used, &total) == 2 && total > 0) {
                                 int val = (int)((float)used / total * 100);
                                 if (val > 100) val = 100;
                                 if (val < 0) val = 0;
                                 adreno->metrics.load = val;
                            }
                        }
                    }
                    std::this_thread::sleep_for(std::chrono::milliseconds(200));
                }
            }).detach();

            SPDLOG_INFO("Adreno GPU injected with background monitoring thread");
        }
    }

    if (total_active < 2) return;
    for (auto& gpu : available_gpus) {
        if (!gpu->is_active) continue;
        break;
    }
}

std::string GPUS::get_driver(const std::string& node) {
    std::string path = "/sys/class/drm/" + node + "/device/driver";
    try {
        if (!fs::exists(path) || !fs::is_symlink(path)) return "";
        std::string driver = fs::read_symlink(path).string();
        return driver.substr(driver.rfind("/") + 1);
    } catch (...) { return ""; }
}

std::string GPUS::get_pci_device_address(const std::string& drm_card_path) {
    try {
        if (!fs::exists(drm_card_path + "/device/subsystem")) return "";
        auto subsystem = fs::canonical(drm_card_path + "/device/subsystem").string();
        if (subsystem.substr(subsystem.rfind("/") + 1) != "pci") return "";
        auto pci_addr = fs::read_symlink(drm_card_path + "/device").string();
        return pci_addr.substr(pci_addr.rfind("/") + 1); 
    } catch (...) { return ""; }
}

int GPU::index_in_selected_gpus() {
    auto selected_gpus = gpus->selected_gpus();
    auto it = std::find_if(selected_gpus.begin(), selected_gpus.end(),
                        [this](const std::shared_ptr<GPU>& gpu) { return gpu.get() == this; });
    return (it != selected_gpus.end()) ? std::distance(selected_gpus.begin(), it) : -1;
}

std::string GPU::gpu_text() {
    size_t index = this->index_in_selected_gpus();
    if (gpus->selected_gpus().size() <= 1) {
        return (gpus->params()->gpu_text.size() > 0) ? gpus->params()->gpu_text[0] : "GPU";
    }
    return (gpus->params()->gpu_text.size() > index) ? gpus->params()->gpu_text[index] : "GPU" + std::to_string(index);
}

std::string GPU::vram_text() {
    return (gpus->selected_gpus().size() > 1) ? "VRAM" + std::to_string(this->index_in_selected_gpus()) : "VRAM";
}

std::shared_ptr<const overlay_params> GPUS::params() {
    return get_params();
}

std::unique_ptr<GPUS> gpus = nullptr;