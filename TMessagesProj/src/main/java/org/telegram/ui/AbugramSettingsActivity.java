package org.telegram.ui;

import android.view.View;

import org.telegram.messenger.AbugramConfig;
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
    private static final int ID_HIDE_SPONSORED = 6;
    private static final int ID_HIDE_STORIES = 7;
    private static final int ID_SHOW_IDS = 8;
    private static final int ID_PROXY = 9;

    @Override
    protected CharSequence getTitle() {
        return "Abugram";
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        AbugramConfig.load();

        items.add(UItem.asHeader("Ko'rinmas rejim / Режим невидимки"));
        items.add(UItem.asCheck(ID_GHOST_READ, "\"O'qildi\" belgisini yubormaslik").setChecked(AbugramConfig.ghostRead));
        items.add(UItem.asCheck(ID_GHOST_TYPING, "\"Yozmoqda...\" ko'rsatmaslik").setChecked(AbugramConfig.ghostTyping));
        items.add(UItem.asCheck(ID_GHOST_ONLINE, "Onlayn ko'rinmaslik").setChecked(AbugramConfig.ghostOnline));
        items.add(UItem.asCheck(ID_GHOST_STORIES, "Story'larni yashirin ko'rish").setChecked(AbugramConfig.ghostStories));
        items.add(UItem.asShadow("Bu sozlamalar Telegram qoidalariga zid bo'lishi mumkin: ehtiyot bo'lib ishlating."));

        items.add(UItem.asHeader("Xabarlar / Сообщения"));
        items.add(UItem.asCheck(ID_KEEP_DELETED, "O'chirilgan xabarlarni saqlab qolish").setChecked(AbugramConfig.keepDeleted));
        items.add(UItem.asCheck(ID_HIDE_SPONSORED, "Homiy (reklama) xabarlarini yashirish").setChecked(AbugramConfig.hideSponsored));
        items.add(UItem.asShadow("O'chirilgan xabarlar faqat shu telefonda, ilova ochiq paytda kelgan o'chirishlar uchun saqlanadi."));

        items.add(UItem.asHeader("Ko'rinish / Внешний вид"));
        items.add(UItem.asCheck(ID_HIDE_STORIES, "Story panelini yashirish").setChecked(AbugramConfig.hideStories));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader("VPN'siz ulanish / Без VPN"));
        items.add(UItem.asButton(ID_PROXY, 0, "Proxy ro'yxati va almashtirish"));
        items.add(UItem.asShadow("Abugram o'zi ishlaydigan proxy'ni topadi va bloklansa keyingisiga o'tadi. " +
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
            case ID_HIDE_SPONSORED:
                value = AbugramConfig.hideSponsored = !AbugramConfig.hideSponsored;
                break;
            case ID_HIDE_STORIES:
                value = AbugramConfig.hideStories = !AbugramConfig.hideStories;
                break;
            case ID_SHOW_IDS:
                value = AbugramConfig.showIds = !AbugramConfig.showIds;
                break;
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
