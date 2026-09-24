#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""后台定时提醒：到点触发提醒；到点后超过宽限期仍未汇报就记一次「未汇报」。

设计要点：
- 用守护线程轮询，主线程照旧阻塞在 input() 上，所以你随时都能打字，提醒不会打断输入；
- 回调统一在锁外调用，避免和主线程的控制台锁互相等待造成死锁；
- tick() 可以注入 now，方便测试时不真的等时间；
- 程序没在运行的时间点不补提醒（超过宽限期就静默跳过），避免一启动被一堆提醒刷屏。
"""

import threading
import traceback
from datetime import datetime, timedelta

DEFAULT_POLL_SECONDS = 5


def parse_checkpoint(text):
    """把 "HH:MM" 或 "HH:MM:SS" 解析成 (hour, minute, second)，非法则抛 ValueError。"""
    parts = str(text).strip().split(":")
    if len(parts) not in (2, 3):
        raise ValueError("提醒时间点格式应为 HH:MM，收到：{!r}".format(text))
    try:
        numbers = [int(part) for part in parts]
    except ValueError:
        raise ValueError("提醒时间点必须是数字，收到：{!r}".format(text))

    hour, minute = numbers[0], numbers[1]
    second = numbers[2] if len(parts) == 3 else 0
    if not (0 <= hour <= 23 and 0 <= minute <= 59 and 0 <= second <= 59):
        raise ValueError("提醒时间点超出范围，收到：{!r}".format(text))
    return hour, minute, second


class ReminderScheduler(object):
    """定时提醒调度器。

    参数：
        checkpoints   : 时间点列表，如 ("12:00", "17:00", "22:00")
        grace_minutes : 宽限期（分钟）；到点后这么久还没汇报，就记一次「未汇报」
        on_remind     : 回调(slot, deadline)，到点时调用
        on_missed     : 回调(slot)，宽限期内没收到汇报时调用
        poll_seconds  : 轮询间隔
        clock         : 取当前时间的函数，默认 datetime.now（测试时可注入）
    """

    def __init__(self, checkpoints, grace_minutes=30, on_remind=None,
                 on_missed=None, poll_seconds=DEFAULT_POLL_SECONDS, clock=None):
        self.checkpoints = []
        for item in checkpoints:
            hour, minute, second = parse_checkpoint(item)
            self.checkpoints.append((str(item), hour, minute, second))
        self.checkpoints.sort(key=lambda item: (item[1], item[2], item[3]))

        self.grace = timedelta(minutes=grace_minutes)
        self.on_remind = on_remind if callable(on_remind) else (lambda slot, deadline: None)
        self.on_missed = on_missed if callable(on_missed) else (lambda slot: None)
        self.poll_seconds = max(1, int(poll_seconds))
        self._clock = clock if callable(clock) else datetime.now

        self._lock = threading.RLock()
        self._stop_event = threading.Event()
        self._thread = None

        self._day = None        # 当前追踪的日期
        self._fired = set()     # 今天已经提醒过的时间点
        self._missed = set()    # 今天已经记过「未汇报」的时间点
        self._pending = None    # 当前等待汇报的时间点
        self._deadline = None   # 该时间点的宽限截止时刻

    # -- 对外接口 --------------------------------------------------------

    def start(self):
        """启动后台线程（重复调用无副作用）。"""
        with self._lock:
            if self._thread is not None:
                return
            self._thread = threading.Thread(target=self._run, name="sk-reminder", daemon=True)
            self._thread.start()

    def stop(self):
        """请求后台线程退出。"""
        self._stop_event.set()

    def notify_report(self):
        """主线程收到「汇报」时调用；返回被满足的提醒时间点，没有则返回 None。"""
        with self._lock:
            slot = self._pending
            self._pending = None
            self._deadline = None
            return slot

    def pending_slot(self):
        """当前等待汇报的时间点（没有则 None）。"""
        with self._lock:
            return self._pending

    def next_checkpoint(self, now=None):
        """今天下一个还没到点的时间点；今天的都已过则返回 None。"""
        now = now or self._clock()
        for label, hour, minute, second in self.checkpoints:
            due = now.replace(hour=hour, minute=minute, second=second, microsecond=0)
            if due > now:
                return label
        return None

    def checkpoints_text(self):
        """把时间点拼成一行，供界面显示。"""
        return "、".join(label for label, _, _, _ in self.checkpoints)

    def tick(self, now=None):
        """检查一次，返回 (本次触发的提醒列表, 本次记录的未汇报列表)。

        now 参数只用于测试注入；正常运行时由 clock 提供。
        """
        now = now or self._clock()
        reminders = []
        misses = []

        with self._lock:
            stale = self._roll_day(now)
            if stale is not None:
                misses.append(stale)

            # 宽限期到了还没汇报 -> 记一次未汇报
            if self._pending is not None and self._deadline is not None and now >= self._deadline:
                misses.append(self._pending)
                self._missed.add(self._pending)
                self._pending = None
                self._deadline = None

            for label, hour, minute, second in self.checkpoints:
                if label in self._fired:
                    continue
                due = now.replace(hour=hour, minute=minute, second=second, microsecond=0)
                if now < due:
                    break
                self._fired.add(label)
                if now - due > self.grace:
                    continue  # 错过的太久（程序当时没运行），既不补提醒也不记未汇报
                if self._pending is not None:
                    # 上一个时间点还没结账就来了新的：把上一个记为未汇报
                    misses.append(self._pending)
                    self._missed.add(self._pending)
                self._pending = label
                self._deadline = due + self.grace
                reminders.append((label, self._deadline))
                break  # 一次只补一个，避免刷屏

        for slot, deadline in reminders:
            self._safe_call(self.on_remind, slot, deadline)
        for slot in misses:
            self._safe_call(self.on_missed, slot)
        return reminders, misses

    # -- 内部实现 --------------------------------------------------------

    def _run(self):
        while not self._stop_event.is_set():
            try:
                self.tick()
            except Exception:  # 定时线程绝不能因为一次异常就静默退出
                traceback.print_exc()
            self._stop_event.wait(self.poll_seconds)

    def _roll_day(self, now):
        """跨天时重置追踪状态；返回跨天前遗留且已超时的待汇报时间点。"""
        today = now.date()
        if self._day == today:
            return None

        stale = None
        if self._pending is not None and self._deadline is not None and now >= self._deadline:
            stale = self._pending

        self._day = today
        self._fired = set()
        self._missed = set()
        self._pending = None
        self._deadline = None
        return stale

    @staticmethod
    def _safe_call(callback, *args):
        """回调出错只打印，不影响调度线程。"""
        try:
            callback(*args)
        except Exception:
            traceback.print_exc()
