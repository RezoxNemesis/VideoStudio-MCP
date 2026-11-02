/** Phone notifications for agent work: job started / finished, render saved, file arrived. Each can be switched off. */
import { Capacitor } from "@capacitor/core";
import { LocalNotifications } from "@capacitor/local-notifications";
import { kvGet, kvSet } from "../core/storage";

export interface NotifySettings {
  enabled: boolean;
  jobStart: boolean;
  jobDone: boolean;
  render: boolean;
  inbox: boolean;
  /** Also notify while the app is open on screen. */
  whileOpen: boolean;
}

export type NotifyKind = "jobStart" | "jobDone" | "render" | "inbox";

export class Notifier {
  settings: NotifySettings = { enabled: false, jobStart: true, jobDone: true, render: true, inbox: true, whileOpen: false };
  private id = 1000;
  private native = Capacitor.isNativePlatform();

  async load(): Promise<void> {
    const v = await kvGet<NotifySettings>("notify").catch(() => undefined);
    if (v) this.settings = { ...this.settings, ...v };
    if (this.native) {
      await LocalNotifications.createChannel({ id: "agent", name: "Agent & renders", description: "Claude jobs, renders and incoming files", importance: 4, visibility: 1 }).catch(() => {});
    }
  }

  async save(): Promise<void> {
    await kvSet("notify", this.settings);
  }

  /** Ask Android for permission (Android 13+ shows the system prompt). Returns whether notifications are allowed. */
  async enable(): Promise<boolean> {
    let ok = false;
    if (this.native) {
      const cur = await LocalNotifications.checkPermissions().catch(() => ({ display: "denied" as const }));
      ok = cur.display === "granted" || (await LocalNotifications.requestPermissions().catch(() => ({ display: "denied" as const }))).display === "granted";
    } else if (typeof Notification !== "undefined") {
      ok = Notification.permission === "granted" || (await Notification.requestPermission()) === "granted";
    }
    this.settings.enabled = ok;
    await this.save();
    return ok;
  }

  async disable(): Promise<void> {
    this.settings.enabled = false;
    await this.save();
  }

  async send(kind: NotifyKind, title: string, body: string): Promise<void> {
    const s = this.settings;
    if (!s.enabled || !s[kind]) return;
    if (!document.hidden && !s.whileOpen) return;
    const id = this.id++;
    try {
      if (this.native) {
        await LocalNotifications.schedule({ notifications: [{ id, title, body, channelId: "agent", smallIcon: "ic_stat_notify", autoCancel: true, extra: { kind } }] });
      } else if (typeof Notification !== "undefined" && Notification.permission === "granted") {
        new Notification(title, { body });
      }
    } catch {
      // A missing small icon or a revoked permission must never break agent work.
      if (this.native) await LocalNotifications.schedule({ notifications: [{ id, title, body, channelId: "agent" }] }).catch(() => {});
    }
  }
}
