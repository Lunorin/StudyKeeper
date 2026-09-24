#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""时钟校准：用 SNTP 协议向 NTP 服务器取时间，算出本机时钟的偏移量。

纯标准库实现（socket + struct + datetime），不引入任何第三方依赖：
- 只发一个 48 字节的 NTP v4 客户端包，读回服务器的收 / 发时间戳；
- offset > 0 表示本机时钟比真实时间慢，offset < 0 表示本机快；
- 校准失败（UDP 123 被拦、超时、响应异常）静默退回系统时钟，绝不影响对话。

用法：
    c = Clock()
    c.sync()
    print(c.describe(), c.now())
"""

import socket
import struct
import time
from datetime import datetime, timedelta

NTP_PORT = 123
# NTP 时间戳以 1900-01-01 为起点，Unix 时间戳以 1970-01-01 为起点
NTP_EPOCH_DELTA = 2208988800
UNIX_EPOCH = datetime(1970, 1, 1)

DEFAULT_SERVERS = (
    "ntp.aliyun.com",
    "time1.cloud.tencent.com",
    "cn.pool.ntp.org",
    "pool.ntp.org",
)
DEFAULT_TIMEOUT = 1.5   # 单个服务器超时（秒）
DEFAULT_BUDGET = 3.0    # 整轮校准的总预算（秒），避免启动被卡住

SYSTEM_SOURCE = "系统时钟"


def ntp_to_unix(seconds, fraction=0):
    """把 NTP 时间戳（整秒 + 32 位小数）换算成 Unix 时间戳。"""
    return float(seconds) - NTP_EPOCH_DELTA + float(fraction) / 4294967296.0


def format_duration(seconds):
    """把秒数说成人话，如「3 小时 10 分钟」「8 分钟」「45 秒」「刚刚」。"""
    total = int(max(0.0, float(seconds)))
    if total == 0:
        return "刚刚"
    if total < 60:
        return "%d 秒" % total
    minutes = total // 60
    if minutes < 60:
        return "%d 分钟" % minutes
    hours, minutes = divmod(minutes, 60)
    if hours < 24:
        return ("%d 小时 %d 分钟" % (hours, minutes)) if minutes else ("%d 小时" % hours)
    days, hours = divmod(hours, 24)
    return ("%d 天 %d 小时" % (days, hours)) if hours else ("%d 天" % days)


def query(server, timeout=DEFAULT_TIMEOUT):
    """向单个 NTP 服务器取时间，返回 (offset_seconds, rtt_seconds)。

    用标准 NTP 偏移公式：offset = ((t2 - t1) + (t3 - t4)) / 2，
    t1 本机发包时刻、t2 服务器收包时刻、t3 服务器发包时刻、t4 本机收包时刻。
    异常交给 sync() 统一兜底。
    """
    packet = b"\x1b" + 47 * b"\x00"       # LI=0, VN=3, Mode=3（客户端）
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.settimeout(timeout)
    try:
        t1 = time.time()
        sock.sendto(packet, (server, NTP_PORT))
        data, _ = sock.recvfrom(1024)
        t4 = time.time()
    finally:
        sock.close()

    if len(data) < 48:
        raise ValueError("响应长度不足 48 字节")

    if data[0] >> 6 == 3:
        raise ValueError("服务器时钟未同步")
    stratum = data[1]
    if stratum == 0 or stratum > 15:
        raise ValueError("服务器不可用（stratum=%d）" % stratum)

    receive_seconds, receive_fraction = struct.unpack("!II", data[32:40])
    transmit_seconds, transmit_fraction = struct.unpack("!II", data[40:48])
    if transmit_seconds == 0:
        raise ValueError("服务器没有返回时间戳")

    t2 = ntp_to_unix(receive_seconds, receive_fraction)
    t3 = ntp_to_unix(transmit_seconds, transmit_fraction)
    offset = ((t2 - t1) + (t3 - t4)) / 2.0
    rtt = (t4 - t1) - (t3 - t2)
    return offset, max(0.0, rtt)


def sync(servers=DEFAULT_SERVERS, timeout=DEFAULT_TIMEOUT, budget=DEFAULT_BUDGET):
    """依次尝试服务器，返回 (offset, server, rtt, errors)；全失败时 offset 为 None。"""
    started = time.time()
    errors = []
    for server in servers:
        if time.time() - started > budget:
            errors.append("超出 %.1f 秒校准预算，停止尝试" % budget)
            break
        try:
            offset, rtt = query(server, timeout)
        except ValueError as exc:
            errors.append("%s: %s" % (server, exc))
            continue
        except OSError as exc:
            errors.append("%s: %s" % (server, exc.__class__.__name__))
            continue
        return offset, server, rtt, errors
    return None, None, None, errors


class Clock(object):
    """带 NTP 偏移量的时钟；没校准成功时就是系统时钟。"""

    def __init__(self, servers=DEFAULT_SERVERS, timeout=DEFAULT_TIMEOUT,
                 budget=DEFAULT_BUDGET, enabled=True):
        self.servers = tuple(servers)
        self.timeout = timeout
        self.budget = budget
        self.enabled = enabled
        self.offset = 0.0
        self.source = SYSTEM_SOURCE
        self.rtt = None
        self.errors = []

    def sync(self):
        """尝试一次 NTP 校准；返回 True / False，任何失败都不抛异常。"""
        if not self.enabled:
            self.source = SYSTEM_SOURCE
            return False
        offset, server, rtt, errors = sync(self.servers, self.timeout, self.budget)
        self.errors = errors
        if offset is None:
            return False
        self.offset = offset
        self.rtt = rtt
        self.source = server
        return True

    def now(self):
        """当前时间（已按 NTP 偏移校正）。"""
        return datetime.now() + timedelta(seconds=self.offset)

    def is_synced(self):
        return self.source != SYSTEM_SOURCE

    def describe(self):
        """给启动横幅用的一句话说明。"""
        if self.is_synced():
            return "NTP %s（时差 %+.2f 秒，往返 %.0f ms）" % (
                self.source, self.offset, (self.rtt or 0.0) * 1000.0)
        if not self.enabled:
            return "系统时钟（NTP 已关闭）"
        reason = "；".join(self.errors[:2]) if self.errors else "未校准"
        return "系统时钟（NTP 未成功：%s）" % reason
