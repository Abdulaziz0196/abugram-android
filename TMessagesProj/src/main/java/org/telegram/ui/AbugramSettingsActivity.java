package org.telegram.ui;

import android.view.View;

import org.telegram.messenger.AbugramConfig;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;

/** Settings → Abugram: the Abugram feature switches. */
public class AbugramSettingsActivity extends UniversalFragment {

    private static final int ID_GHOST_READ = 1;
    private static final int ID_GHOST_TYPING = 2;
    private static final int ID_GHOST_ONLINE = 3;
    private static final int ID_GHOST_STORIES = 4;
    private static final int ID_KEEP_DELETED = 5;
    private static final int ID_HIDE_STORIES = 7;
    private static final int ID_SHOW_IDS = 8;
    private static final int ID_PROXY = 9;
    private static final int ID_ADMIN = 12;
    private static final int ID_SHOW_SECONDS = 10;
    private static final int ID_CONFIRM_CALLS = 11;

    @Override
    protected CharSequence getTitle() {
        return "Abugram";
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        AbugramConfig.load();

        if (AbugramConfig.isAdmin) {
            items.add(UItem.asButton(ID_ADMIN, 0, "Admin panel"));
            items.add(UItem.asShadow("Reklamalar, statistika va ruxsat berilganlar."));
        }

        if (AbugramConfig.ghostAllowed) {
            items.add(UItem.asHeader("Ko'rinmas rejim / Режим невидимки"));
            items.add(UItem.asCheck(ID_GHOST_READ, "\"O'qildi\" belgisini yubormaslik").setChecked(AbugramConfig.ghostRead));
            items.add(UItem.asCheck(ID_GHOST_TYPING, "\"Yozmoqda...\" ko'rsatmaslik").setChecked(AbugramConfig.ghostTyping));
            items.add(UItem.asCheck(ID_GHOST_ONLINE, "Onlayn ko'rinmaslik").setChecked(AbugramConfig.ghostOnline));
            items.add(UItem.asCheck(ID_GHOST_STORIES, "Story'larni yashirin ko'rish").setChecked(AbugramConfig.ghostStories));
            items.add(UItem.asShadow("Bu sozlamalar Telegram qoidalariga zid bo'lishi mumkin: ehtiyot bo'lib ishlating."));
        }

        if (AbugramConfig.ghostAllowed) {
            items.add(UItem.asHeader("Xabarlar / Сообщения"));
            items.add(UItem.asCheck(ID_KEEP_DELETED, "O'chirilgan xabarlarni saqlab qolish").setChecked(AbugramConfig.keepDeleted));
            items.add(UItem.asShadow("O'chirilgan xabarlar faqat shu telefonda, ilova ochiq paytda kelgan o'chirishlar uchun saqlanadi."));
        }

        items.add(UItem.asHeader("Ko'rinish / Внешний вид"));
        items.add(UItem.asCheck(ID_HIDE_STORIES, "Story panelini yashirish").setChecked(AbugramConfig.hideStories));
        items.add(UItem.asCheck(ID_SHOW_SECONDS, "Vaqtni soniyagacha ko'rsatish").setChecked(AbugramConfig.showSeconds));
        items.add(UItem.asCheck(ID_SHOW_IDS, "Profilda ID ko'rsatish").setChecked(AbugramConfig.showIds));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader("Qo'ng'iroqlar / Звонки"));
        items.add(UItem.asCheck(ID_CONFIRM_CALLS, "Qo'ng'iroqdan oldin so'rash").setChecked(AbugramConfig.confirmCalls));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader("Diagnostika / Диагностика"));
        java.util.List<org.telegram.messenger.AbugramRelayBridge.Stat> stats = org.telegram.messenger.AbugramRelayBridge.getStats();
        if (stats.isEmpty()) {
            items.add(UItem.asShadow("Hali so'rov yo'q."));
        } else {
            StringBuilder b = new StringBuilder();
            int count = 0;
            for (org.telegram.messenger.AbugramRelayBridge.Stat st : stats) {
                if (count++ >= 20) break;
                b.append("DC").append(st.dc).append(" · ").append(st.millis).append(" ms · ⬆")
                        .append(st.bytesOut / 1024).append(" KB ⬇").append(st.bytesIn / 1024).append(" KB · ")
                        .append(st.result).append('\n');
            }
            items.add(UItem.asShadow(b.toString().trim()));
        }
        items.add(UItem.asShadow("Ovozli yoki video xabar yuborilmasa, shu ro'yxatning skrinshotini yuboring: qaysi so'rov uzoq ketayotganini ko'rsatadi."));

        items.add(UItem.asHeader("VPN'siz ulanish / Без VPN"));
        items.add(UItem.asButton(ID_PROXY, 0, "Proxy ro'yxati va almashtirish"));
        boolean viaRelay = SharedConfig.currentProxy != null && "127.0.0.1".equals(SharedConfig.currentProxy.settings.getAddress());
        items.add(UItem.asShadow(viaRelay
                ? "Hozir: Abugram relay orqali (oddiy HTTPS so'rovlar). VPN kerak emas."
                : "Abugram Telegram'ga o'zining relay'i orqali oddiy HTTPS so'rovlar bilan ulanadi. " +
                (SharedConfig.currentProxy != null ? "Hozir: " + SharedConfig.currentProxy.settings.getAddress() : "")));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        boolean value;
        switch (item.id) {
            case ID_GHOST_READ:
                value = AbugramConfig.ghostRead = !AbugramConfig.ghostRead;
                break;
            case ID_GHOST_TYPING:
                value = AbugramConfig.ghostTyping = !AbugramConfig.ghostTyping;
                break;
            case ID_GHOST_ONLINE:
                value = AbugramConfig.ghostOnline = !AbugramConfig.ghostOnline;
                break;
            case ID_GHOST_STORIES:
                value = AbugramConfig.ghostStories = !AbugramConfig.ghostStories;
                break;
            case ID_KEEP_DELETED:
                value = AbugramConfig.keepDeleted = !AbugramConfig.keepDeleted;
                break;
            case ID_HIDE_STORIES:
                value = AbugramConfig.hideStories = !AbugramConfig.hideStories;
                break;
            case ID_SHOW_IDS:
                value = AbugramConfig.showIds = !AbugramConfig.showIds;
                break;
            case ID_SHOW_SECONDS:
                value = AbugramConfig.showSeconds = !AbugramConfig.showSeconds;
                LocaleController.getInstance().abugramResetFormatterDay();
                break;
            case ID_CONFIRM_CALLS:
                value = AbugramConfig.confirmCalls = !AbugramConfig.confirmCalls;
                break;
            case ID_ADMIN:
                org.telegram.messenger.browser.Browser.openUrl(getParentActivity(), "https://abdulaziz0196.github.io/tashlanov.ru/abugram/admin.html");
                return;
            case ID_PROXY:
                presentFragment(new ProxyListActivity());
                return;
            default:
                return;
        }
        AbugramConfig.save();
        if (view instanceof TextCheckCell) {
            ((TextCheckCell) view).setChecked(value);
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }
}
