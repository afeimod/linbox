#include "cpu.h"
#include <memory>
#include <string>
#include <iostream>
#include <fstream>
#include <sstream>
#include <vector>
#include <numeric>
#include <algorithm>
#include <thread>
#include <chrono>
#include <unistd.h>
#include <spdlog/spdlog.h>
#include "gpu.h"
#include "file_utils.h"

// 记录上一帧的状态
static unsigned long long prev_proc_ticks = 0;
static std::chrono::steady_clock::time_point prev_time;
static bool is_first_run = true;
static long clk_tck = 100;
static int num_cores = 1;

// 读取当前进程的 CPU 时间 (utime + stime)
static unsigned long long get_self_cpu_ticks() {
    std::ifstream file("/proc/self/stat");
    if (!file.is_open()) return 0;

    std::string line;
    std::getline(file, line);
    
    // /proc/self/stat 格式复杂，第二项 (comm) 可能包含空格和括号
    // 安全的做法是找到最后一个 ')'，然后从那里开始解析
    size_t last_parenthesis = line.find_last_of(')');
    if (last_parenthesis == std::string::npos || last_parenthesis + 2 >= line.length()) return 0;

    std::stringstream ss(line.substr(last_parenthesis + 2)); // 跳过 ") "
    
    // stat 文件中，从 ')' 后面开始数：
    // state(3), ppid(4), pgrp(5), session(6), tty_nr(7), tpgid(8), flags(9), 
    // minflt(10), cminflt(11), majflt(12), cmajflt(13), utime(14), stime(15)
    // 我们需要的是 utime 和 stime，它们现在分别是流中的第 12 和 13 个数字 (因为前面截断了前2项)
    
    std::string val;
    unsigned long long utime = 0, stime = 0;
    
    // 跳过前 11 项 (3-13)
    for (int i = 0; i < 11; i++) ss >> val;
    
    ss >> utime >> stime;
    return utime + stime;
}

static void update_process_usage(CPUData& cpuDataTotal) {
    unsigned long long cur_ticks = get_self_cpu_ticks();
    auto cur_time = std::chrono::steady_clock::now();

    if (is_first_run) {
        // 初始化基础参数
        clk_tck = sysconf(_SC_CLK_TCK);
        num_cores = sysconf(_SC_NPROCESSORS_ONLN);
        if (num_cores < 1) num_cores = 1;
        if (clk_tck < 1) clk_tck = 100;

        prev_proc_ticks = cur_ticks;
        prev_time = cur_time;
        is_first_run = false;
        return;
    }

    // 计算时间差 (秒)
    std::chrono::duration<float> elapsed_seconds = cur_time - prev_time;
    float dt = elapsed_seconds.count();

    // 只有当时间间隔足够长（例如 0.5秒），且有 tick 变化时才更新
    // 这样可以避免跳变
    if (dt > 0.0f) {
        unsigned long long tick_diff = 0;
        if (cur_ticks > prev_proc_ticks) tick_diff = cur_ticks - prev_proc_ticks;

        // 公式: (Tick差 / 赫兹) / 时间差 / 核心数 * 100
        // 如果不除以核心数，多核占用会超过 100%，MangoHud 通常显示归一化后的 System Load
        float cpu_usage = ((float)tick_diff / (float)clk_tck) / dt * 100.0f;
        
        // 归一化到系统总占用 (类似 Windows 任务管理器)
        cpu_usage /= (float)num_cores;

        // 限制范围
        if (cpu_usage > 100.0f) cpu_usage = 100.0f;
        if (cpu_usage < 0.0f) cpu_usage = 0.0f;

        cpuDataTotal.percent = cpu_usage;

        prev_proc_ticks = cur_ticks;
        prev_time = cur_time;
    }
}

CPUStats::CPUStats() {}
CPUStats::~CPUStats() { if (m_cpuTempFile) fclose(m_cpuTempFile); }

bool CPUStats::Init() {
    m_inited = true;
    if (m_cpuData.empty()) {
        for(int i=0; i<8; i++) {
            CPUData cpu = {};
            cpu.cpu_id = i;
            m_cpuData.push_back(cpu);
        }
    }
    return true;
}

bool CPUStats::Reinit() { return Init(); }

bool CPUStats::UpdateCPUData() {
    // 改为监控当前进程
    update_process_usage(m_cpuDataTotal);
    m_updatedCPUs = true;
    return true;
}

bool CPUStats::UpdateCoreMhz() { return true; }

bool CPUStats::ReadcpuTempFile(int& temp) {
    if (!m_cpuTempFile) return false;
    rewind(m_cpuTempFile);
    bool ret = (fscanf(m_cpuTempFile, "%d", &temp) == 1);
    temp = temp / 1000;
    return ret;
}

bool CPUStats::UpdateCpuTemp() {
    // 优先读取 GPU 模块的温度 (SoC 共享)
    if (gpus && !gpus->available_gpus.empty()) {
        m_cpuDataTotal.temp = gpus->available_gpus[0]->metrics.temp;
    } else {
        // 备选: thermal_zone0
        FILE* fp = fopen("/sys/class/thermal/thermal_zone0/temp", "r");
        if (fp) {
            int t;
            if (fscanf(fp, "%d", &t) == 1) m_cpuDataTotal.temp = t / 1000;
            fclose(fp);
        }
    }
    return true;
}

bool CPUStats::UpdateCpuPower() { return true; }
bool CPUStats::GetCpuFile() { return true; }
bool CPUStats::InitCpuPowerData() { return true; }
void CPUStats::get_cpu_cores_types() {}
void CPUStats::get_cpu_cores_types_intel() {}
void CPUStats::get_cpu_cores_types_arm() {}

CPUStats cpuStats;