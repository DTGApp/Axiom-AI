
/*
 * Axiom — On-Device AI Assistant for Android
 * Copyright (C) 2024 [Your Name]
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package com.axiom.axiomnew

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log

// ════════════════════════════════════════════════════════════════════════════════
//  AppIntentRegistry — Dynamic intent resolver for installed apps
//
//  Two-layer approach:
//
//  Layer 1 — Static database (KNOWN_APPS):
//    100+ apps with curated deep-link URI templates for search, call, message,
//    video-call, navigate, shop, play, watch, post. Only activated for apps
//    that are actually installed on this device.
//
//  Layer 2 — Dynamic scanner:
//    Scans every installed app that is NOT in the static database.
//    If it responds to ACTION_SEARCH → registered as a searchable app.
//    If it responds to ACTION_SEND   → registered as a shareable app.
//    This means any app — including ones that don't exist yet — is
//    automatically supported the moment the user installs it.
//
//  Usage:
//    AppIntentRegistry.init(context)          // call once in AxiomService.onCreate()
//    AppIntentRegistry.resolveSearch(pkg, query) → Intent?
//    AppIntentRegistry.resolveAction(action, pkg, extra) → Intent?
//    AppIntentRegistry.findApp(namePart) → AppProfile?
//    AppIntentRegistry.findInstalledApps() → List<AppProfile>
// ════════════════════════════════════════════════════════════════════════════════
object AppIntentRegistry {

    private const val TAG = "AppIntentRegistry"

    // ── Action types ──────────────────────────────────────────────────────────
    enum class AppAction { SEARCH, CALL, MESSAGE, VIDEO_CALL, PLAY, WATCH,
        NAVIGATE, SHOP, POST, SEND, LAUNCH }

    // ── App profile ───────────────────────────────────────────────────────────
    data class AppProfile(
        val pkg:          String,
        val displayName:  String,
        val aliases:      List<String>,          // lowercase voice-match keywords
        val searchUri:    String?  = null,       // {q} = encoded query
        val callUri:      String?  = null,       // {number} = phone number
        val messageUri:   String?  = null,       // {number}/{q} placeholders
        val videoCallUri: String?  = null,
        val playUri:      String?  = null,
        val watchUri:     String?  = null,
        val shopUri:      String?  = null,
        val postUri:      String?  = null,
        val supportsSearch: Boolean = false,     // set true by dynamic scanner
        val supportsSend:   Boolean = false,
        var isInstalled:  Boolean  = false       // set at runtime
    )

    // ── Runtime state ─────────────────────────────────────────────────────────
    private var ctx: Context? = null

    // All profiles from static DB that are installed, keyed by package
    private val installedProfiles = mutableMapOf<String, AppProfile>()

    // Dynamic apps (not in static DB) that support ACTION_SEARCH
    private val dynamicSearchApps = mutableMapOf<String, String>()  // pkg → display name

    // Master alias index: lowercase alias → pkg  (for voice matching)
    private val aliasIndex = mutableMapOf<String, String>()

    // ═════════════════════════════════════════════════════════════════════════
    //  Static database — curated deep links for 100+ apps
    // ═════════════════════════════════════════════════════════════════════════
    private val KNOWN_APPS: List<AppProfile> = listOf(

        // ── Communication ─────────────────────────────────────────────────────
        AppProfile(
            pkg         = "com.whatsapp",
            displayName = "WhatsApp",
            aliases     = listOf("whatsapp", "whats app", "wa", "wapp"),
            searchUri   = null,
            callUri     = "https://wa.me/{number}",
            messageUri  = "https://wa.me/{number}?text={q}",
            videoCallUri = "https://wa.me/{number}"   // opens chat; user taps video
        ),
        AppProfile(
            pkg         = "com.whatsapp.w4b",
            displayName = "WhatsApp Business",
            aliases     = listOf("whatsapp business", "wa business"),
            callUri     = "https://wa.me/{number}",
            messageUri  = "https://wa.me/{number}?text={q}"
        ),
        AppProfile(
            pkg         = "org.telegram.messenger",
            displayName = "Telegram",
            aliases     = listOf("telegram", "tg"),
            searchUri   = "tg://resolve?domain={q}",
            messageUri  = "tg://resolve?domain={q}&text={msg}"
        ),
        AppProfile(
            pkg         = "org.thoughtcrime.securesms",
            displayName = "Signal",
            aliases     = listOf("signal"),
            messageUri  = "sgnl://signal.me/#p/{number}"
        ),
        AppProfile(
            pkg         = "com.instagram.android",
            displayName = "Instagram",
            aliases     = listOf("instagram", "insta", "ig"),
            searchUri   = "https://www.instagram.com/{q}/",
            messageUri  = "https://www.instagram.com/direct/new/"
        ),
        AppProfile(
            pkg         = "com.snapchat.android",
            displayName = "Snapchat",
            aliases     = listOf("snapchat", "snap"),
            searchUri   = "https://www.snapchat.com/add/{q}"
        ),
        AppProfile(
            pkg         = "com.discord",
            displayName = "Discord",
            aliases     = listOf("discord"),
            searchUri   = null,   // no public deep-link search
            messageUri  = null
        ),
        AppProfile(
            pkg         = "com.skype.raider",
            displayName = "Skype",
            aliases     = listOf("skype"),
            callUri     = "skype:{number}?call",
            videoCallUri = "skype:{number}?call&video=true",
            messageUri  = "skype:{number}?chat"
        ),
        AppProfile(
            pkg         = "com.viber.voip",
            displayName = "Viber",
            aliases     = listOf("viber"),
            callUri     = "viber://contact?number={number}",
            messageUri  = "viber://contact?number={number}"
        ),
        AppProfile(
            pkg         = "com.microsoft.teams",
            displayName = "Microsoft Teams",
            aliases     = listOf("teams", "microsoft teams", "ms teams"),
            searchUri   = null,
            callUri     = "https://teams.microsoft.com/l/call/0/0?users={number}"
        ),
        AppProfile(
            pkg         = "us.zoom.videomeetings",
            displayName = "Zoom",
            aliases     = listOf("zoom"),
            videoCallUri = "zoomus://zoom.us/join"
        ),
        AppProfile(
            pkg         = "com.linkedin.android",
            displayName = "LinkedIn",
            aliases     = listOf("linkedin", "linked in"),
            searchUri   = "https://www.linkedin.com/search/results/all/?keywords={q}"
        ),
        AppProfile(
            pkg         = "com.twitter.android",
            displayName = "X (Twitter)",
            aliases     = listOf("twitter", "x", "tweet"),
            searchUri   = "https://twitter.com/search?q={q}",
            postUri     = "https://twitter.com/intent/tweet?text={q}"
        ),
        AppProfile(
            pkg         = "com.facebook.katana",
            displayName = "Facebook",
            aliases     = listOf("facebook", "fb"),
            searchUri   = "fb://search?q={q}"
        ),
        AppProfile(
            pkg         = "com.facebook.orca",
            displayName = "Messenger",
            aliases     = listOf("messenger", "fb messenger", "facebook messenger"),
            messageUri  = "fb-messenger://user-thread/{number}"
        ),
        AppProfile(
            pkg         = "com.reddit.frontpage",
            displayName = "Reddit",
            aliases     = listOf("reddit"),
            searchUri   = "https://www.reddit.com/search?q={q}"
        ),
        AppProfile(
            pkg         = "com.zhiliaoapp.musically",
            displayName = "TikTok",
            aliases     = listOf("tiktok", "tik tok"),
            searchUri   = "https://www.tiktok.com/search?q={q}"
        ),
        AppProfile(
            pkg         = "com.pinterest",
            displayName = "Pinterest",
            aliases     = listOf("pinterest"),
            searchUri   = "https://pinterest.com/search/pins/?q={q}"
        ),
        AppProfile(
            pkg         = "com.quora.android",
            displayName = "Quora",
            aliases     = listOf("quora"),
            searchUri   = "https://www.quora.com/search?q={q}"
        ),

        // ── Entertainment ─────────────────────────────────────────────────────
        AppProfile(
            pkg         = "com.google.android.youtube",
            displayName = "YouTube",
            aliases     = listOf("youtube", "yt", "you tube"),
            searchUri   = "https://www.youtube.com/results?search_query={q}",
            watchUri    = "https://www.youtube.com/results?search_query={q}",
            playUri     = "https://www.youtube.com/results?search_query={q}"
        ),
        AppProfile(
            pkg         = "com.spotify.music",
            displayName = "Spotify",
            aliases     = listOf("spotify"),
            searchUri   = "spotify:search:{q}",
            playUri     = "spotify:search:{q}"
        ),
        AppProfile(
            pkg         = "com.netflix.mediaclient",
            displayName = "Netflix",
            aliases     = listOf("netflix"),
            searchUri   = "https://www.netflix.com/search?q={q}",
            watchUri    = "https://www.netflix.com/search?q={q}"
        ),
        AppProfile(
            pkg         = "com.amazon.avod.thirdpartyclient",
            displayName = "Prime Video",
            aliases     = listOf("prime video", "amazon prime", "prime"),
            searchUri   = "https://www.primevideo.com/search/ref=atv_sr_sug_1?phrase={q}",
            watchUri    = "https://www.primevideo.com/search/ref=atv_sr_sug_1?phrase={q}"
        ),
        AppProfile(
            pkg         = "in.startv.hotstar",
            displayName = "Disney+ Hotstar",
            aliases     = listOf("hotstar", "disney hotstar", "disney plus", "disney+"),
            searchUri   = "https://www.hotstar.com/in/search?q={q}",
            watchUri    = "https://www.hotstar.com/in/search?q={q}"
        ),
        AppProfile(
            pkg         = "com.google.android.apps.youtube.music",
            displayName = "YouTube Music",
            aliases     = listOf("youtube music", "yt music"),
            searchUri   = "https://music.youtube.com/search?q={q}",
            playUri     = "https://music.youtube.com/search?q={q}"
        ),
        AppProfile(
            pkg         = "com.apple.android.music",
            displayName = "Apple Music",
            aliases     = listOf("apple music"),
            searchUri   = "music://music.apple.com/search?term={q}",
            playUri     = "music://music.apple.com/search?term={q}"
        ),
        AppProfile(
            pkg         = "com.soundcloud.android",
            displayName = "SoundCloud",
            aliases     = listOf("soundcloud", "sound cloud"),
            searchUri   = "soundcloud://search?q={q}",
            playUri     = "soundcloud://search?q={q}"
        ),
        AppProfile(
            pkg         = "tv.twitch.android.app",
            displayName = "Twitch",
            aliases     = listOf("twitch"),
            searchUri   = "twitch://search?query={q}",
            watchUri    = "twitch://search?query={q}"
        ),
        AppProfile(
            pkg         = "com.mxtech.videoplayer.ad",
            displayName = "MX Player",
            aliases     = listOf("mx player", "mx")
        ),
        AppProfile(
            pkg         = "com.mxtech.videoplayer.pro",
            displayName = "MX Player Pro",
            aliases     = listOf("mx player pro")
        ),
        AppProfile(
            pkg         = "org.videolan.vlc",
            displayName = "VLC",
            aliases     = listOf("vlc")
        ),
        AppProfile(
            pkg         = "com.jio.media.ondemand",
            displayName = "JioSaavn",
            aliases     = listOf("jiosaavn", "saavn", "jio saavn"),
            searchUri   = "https://www.jiosaavn.com/search/{q}",
            playUri     = "https://www.jiosaavn.com/search/{q}"
        ),
        AppProfile(
            pkg         = "com.gaana",
            displayName = "Gaana",
            aliases     = listOf("gaana"),
            searchUri   = "https://gaana.com/search/{q}",
            playUri     = "https://gaana.com/search/{q}"
        ),
        AppProfile(
            pkg         = "com.wynk.music",
            displayName = "Wynk Music",
            aliases     = listOf("wynk", "wynk music"),
            searchUri   = "https://wynk.in/music/search/{q}"
        ),

        // ── Shopping ──────────────────────────────────────────────────────────
        AppProfile(
            pkg         = "in.amazon.mShoppingApp",
            displayName = "Amazon",
            aliases     = listOf("amazon", "amazon india", "amazon.in"),
            searchUri   = "https://www.amazon.in/s?k={q}",
            shopUri     = "https://www.amazon.in/s?k={q}"
        ),
        AppProfile(
            pkg         = "com.amazon.mShoppingApp",
            displayName = "Amazon",
            aliases     = listOf("amazon us", "amazon.com"),
            searchUri   = "https://www.amazon.com/s?k={q}",
            shopUri     = "https://www.amazon.com/s?k={q}"
        ),
        AppProfile(
            pkg         = "com.flipkart.android",
            displayName = "Flipkart",
            aliases     = listOf("flipkart"),
            searchUri   = "https://www.flipkart.com/search?q={q}",
            shopUri     = "https://www.flipkart.com/search?q={q}"
        ),
        AppProfile(
            pkg         = "com.myntra.android",
            displayName = "Myntra",
            aliases     = listOf("myntra"),
            searchUri   = "https://www.myntra.com/{q}",
            shopUri     = "https://www.myntra.com/{q}"
        ),
        AppProfile(
            pkg         = "com.meesho.supply",
            displayName = "Meesho",
            aliases     = listOf("meesho"),
            searchUri   = "https://meesho.com/search?q={q}",
            shopUri     = "https://meesho.com/search?q={q}"
        ),
        AppProfile(
            pkg         = "com.snapdeal.main",
            displayName = "Snapdeal",
            aliases     = listOf("snapdeal"),
            searchUri   = "https://www.snapdeal.com/search?keyword={q}",
            shopUri     = "https://www.snapdeal.com/search?keyword={q}"
        ),
        AppProfile(
            pkg         = "com.fsn.nykaa",
            displayName = "Nykaa",
            aliases     = listOf("nykaa"),
            searchUri   = "https://www.nykaa.com/search/result/?q={q}",
            shopUri     = "https://www.nykaa.com/search/result/?q={q}"
        ),
        AppProfile(
            pkg         = "com.ebay.mobile",
            displayName = "eBay",
            aliases     = listOf("ebay"),
            searchUri   = "https://www.ebay.com/sch/i.html?_nkw={q}",
            shopUri     = "https://www.ebay.com/sch/i.html?_nkw={q}"
        ),
        AppProfile(
            pkg         = "com.android.vending",
            displayName = "Play Store",
            aliases     = listOf("play store", "google play", "playstore"),
            searchUri   = "market://search?q={q}",
            shopUri     = "market://search?q={q}"
        ),

        // ── Food & Delivery ───────────────────────────────────────────────────
        AppProfile(
            pkg         = "com.application.zomato",
            displayName = "Zomato",
            aliases     = listOf("zomato"),
            searchUri   = "https://www.zomato.com/search?q={q}",
            shopUri     = "https://www.zomato.com/search?q={q}"
        ),
        AppProfile(
            pkg         = "in.swiggy.android",
            displayName = "Swiggy",
            aliases     = listOf("swiggy"),
            searchUri   = "https://swiggy.com/search?query={q}",
            shopUri     = "https://swiggy.com/search?query={q}"
        ),
        AppProfile(
            pkg         = "com.dunzo.user",
            displayName = "Dunzo",
            aliases     = listOf("dunzo")
        ),
        AppProfile(
            pkg         = "com.bigbasket.mobileapp",
            displayName = "BigBasket",
            aliases     = listOf("bigbasket", "big basket"),
            searchUri   = "https://www.bigbasket.com/ps/?q={q}",
            shopUri     = "https://www.bigbasket.com/ps/?q={q}"
        ),
        AppProfile(
            pkg         = "com.grofers.customerapp",
            displayName = "Blinkit",
            aliases     = listOf("blinkit", "grofers"),
            searchUri   = "https://blinkit.com/s/?q={q}",
            shopUri     = "https://blinkit.com/s/?q={q}"
        ),
        AppProfile(
            pkg         = "com.dominos.app",
            displayName = "Dominos",
            aliases     = listOf("dominos", "domino's", "pizza")
        ),
        AppProfile(
            pkg         = "com.mcdelivery.android.delivery",
            displayName = "McDelivery",
            aliases     = listOf("mcdelivery", "mcdonalds", "mcdonald's")
        ),

        // ── Travel & Transport ────────────────────────────────────────────────
        AppProfile(
            pkg         = "com.ubercab",
            displayName = "Uber",
            aliases     = listOf("uber")
        ),
        AppProfile(
            pkg         = "com.olacabs.customer",
            displayName = "Ola",
            aliases     = listOf("ola", "ola cab", "ola cabs")
        ),
        AppProfile(
            pkg         = "com.rapido.passenger",
            displayName = "Rapido",
            aliases     = listOf("rapido")
        ),
        AppProfile(
            pkg         = "com.makemytrip",
            displayName = "MakeMyTrip",
            aliases     = listOf("makemytrip", "mmt"),
            searchUri   = "mmt://hotels?city={q}"
        ),
        AppProfile(
            pkg         = "com.booking",
            displayName = "Booking.com",
            aliases     = listOf("booking.com", "booking"),
            searchUri   = "booking://hotels?dest_name={q}",
            shopUri     = "booking://hotels?dest_name={q}"
        ),
        AppProfile(
            pkg         = "com.airbnb.android",
            displayName = "Airbnb",
            aliases     = listOf("airbnb"),
            searchUri   = "airbnb://search?location={q}",
            shopUri     = "airbnb://search?location={q}"
        ),
        AppProfile(
            pkg         = "com.ixigo.train.ixitrain",
            displayName = "ixigo",
            aliases     = listOf("ixigo")
        ),
        AppProfile(
            pkg         = "com.irctc.irtc",
            displayName = "IRCTC",
            aliases     = listOf("irctc", "indian railways")
        ),
        AppProfile(
            pkg         = "com.redbus.android",
            displayName = "redBus",
            aliases     = listOf("redbus", "red bus")
        ),

        // ── Finance ───────────────────────────────────────────────────────────
        AppProfile(
            pkg         = "com.phonepe.app",
            displayName = "PhonePe",
            aliases     = listOf("phonepe", "phone pe")
        ),
        AppProfile(
            pkg         = "com.google.android.apps.nbu.paisa.user",
            displayName = "Google Pay",
            aliases     = listOf("google pay", "gpay", "g pay")
        ),
        AppProfile(
            pkg         = "net.one97.paytm",
            displayName = "Paytm",
            aliases     = listOf("paytm")
        ),
        AppProfile(
            pkg         = "in.org.npci.upiapp",
            displayName = "BHIM",
            aliases     = listOf("bhim", "bhim upi")
        ),
        AppProfile(
            pkg         = "com.cred.club",
            displayName = "CRED",
            aliases     = listOf("cred")
        ),
        AppProfile(
            pkg         = "com.coinbase.android",
            displayName = "Coinbase",
            aliases     = listOf("coinbase"),
            searchUri   = "coinbase://price/{q}"
        ),
        AppProfile(
            pkg         = "com.zerodha.kite",
            displayName = "Zerodha Kite",
            aliases     = listOf("zerodha", "kite", "zerodha kite"),
            searchUri   = "kite://instrument/{q}"
        ),

        // ── Browsers ──────────────────────────────────────────────────────────
        AppProfile(
            pkg         = "com.android.chrome",
            displayName = "Chrome",
            aliases     = listOf("chrome", "google chrome"),
            searchUri   = "https://www.google.com/search?q={q}"
        ),
        AppProfile(
            pkg         = "org.mozilla.firefox",
            displayName = "Firefox",
            aliases     = listOf("firefox", "mozilla firefox"),
            searchUri   = "https://www.google.com/search?q={q}"
        ),
        AppProfile(
            pkg         = "com.brave.browser",
            displayName = "Brave",
            aliases     = listOf("brave", "brave browser"),
            searchUri   = "https://search.brave.com/search?q={q}"
        ),
        AppProfile(
            pkg         = "com.sec.android.app.sbrowser",
            displayName = "Samsung Browser",
            aliases     = listOf("samsung browser", "samsung internet"),
            searchUri   = "https://www.google.com/search?q={q}"
        ),
        AppProfile(
            pkg         = "com.duckduckgo.mobile.android",
            displayName = "DuckDuckGo",
            aliases     = listOf("duckduckgo", "duck duck go", "ddg"),
            searchUri   = "https://duckduckgo.com/?q={q}"
        ),
        AppProfile(
            pkg         = "com.opera.browser",
            displayName = "Opera",
            aliases     = listOf("opera", "opera browser"),
            searchUri   = "https://www.google.com/search?q={q}"
        ),
        AppProfile(
            pkg         = "com.microsoft.bing",
            displayName = "Bing",
            aliases     = listOf("bing"),
            searchUri   = "https://www.bing.com/search?q={q}"
        ),

        // ── Productivity ──────────────────────────────────────────────────────
        AppProfile(
            pkg         = "com.google.android.gm",
            displayName = "Gmail",
            aliases     = listOf("gmail", "google mail"),
            searchUri   = "googlegmail://co?subject={q}"
        ),
        AppProfile(
            pkg         = "com.google.android.apps.docs",
            displayName = "Google Docs",
            aliases     = listOf("docs", "google docs"),
            searchUri   = "https://docs.google.com/document/?usp=docs_home"
        ),
        AppProfile(
            pkg         = "com.google.android.apps.spreadsheets",
            displayName = "Google Sheets",
            aliases     = listOf("sheets", "google sheets"),
            searchUri   = "https://sheets.google.com/"
        ),
        AppProfile(
            pkg         = "com.google.android.apps.drive",
            displayName = "Google Drive",
            aliases     = listOf("drive", "google drive"),
            searchUri   = "https://drive.google.com/drive/search?q={q}"
        ),
        AppProfile(
            pkg         = "com.microsoft.office.word",
            displayName = "Microsoft Word",
            aliases     = listOf("word", "ms word", "microsoft word")
        ),
        AppProfile(
            pkg         = "com.microsoft.office.excel",
            displayName = "Microsoft Excel",
            aliases     = listOf("excel", "ms excel")
        ),
        AppProfile(
            pkg         = "com.microsoft.office.powerpoint",
            displayName = "Microsoft PowerPoint",
            aliases     = listOf("powerpoint", "ms powerpoint")
        ),
        AppProfile(
            pkg         = "com.evernote",
            displayName = "Evernote",
            aliases     = listOf("evernote"),
            searchUri   = "evernote://widgetsearch/{q}"
        ),
        AppProfile(
            pkg         = "com.notion.id",
            displayName = "Notion",
            aliases     = listOf("notion")
        ),
        AppProfile(
            pkg         = "com.todoist.android",
            displayName = "Todoist",
            aliases     = listOf("todoist", "to-do list")
        ),
        AppProfile(
            pkg         = "com.anydo",
            displayName = "Any.do",
            aliases     = listOf("any.do", "anydo")
        ),

        // ── Games ─────────────────────────────────────────────────────────────
        AppProfile(
            pkg         = "com.pubg.imobile",
            displayName = "BGMI",
            aliases     = listOf("bgmi", "battlegrounds", "battlegrounds mobile india")
        ),
        AppProfile(
            pkg         = "com.tencent.ig",
            displayName = "PUBG Mobile",
            aliases     = listOf("pubg", "pubg mobile")
        ),
        AppProfile(
            pkg         = "com.dts.freefireth",
            displayName = "Free Fire",
            aliases     = listOf("free fire", "freefire", "ff")
        ),
        AppProfile(
            pkg         = "com.dts.freefiremax",
            displayName = "Free Fire MAX",
            aliases     = listOf("free fire max", "ff max")
        ),
        AppProfile(
            pkg         = "com.roblox.client",
            displayName = "Roblox",
            aliases     = listOf("roblox"),
            searchUri   = "https://www.roblox.com/search/users?keyword={q}"
        ),
        AppProfile(
            pkg         = "com.supercell.clashofclans",
            displayName = "Clash of Clans",
            aliases     = listOf("clash of clans", "coc")
        ),
        AppProfile(
            pkg         = "com.supercell.clashroyale",
            displayName = "Clash Royale",
            aliases     = listOf("clash royale")
        ),
        AppProfile(
            pkg         = "com.innersloth.spacemafia",
            displayName = "Among Us",
            aliases     = listOf("among us")
        ),
        AppProfile(
            pkg         = "com.king.candycrushsaga",
            displayName = "Candy Crush",
            aliases     = listOf("candy crush", "candy crush saga")
        ),
        AppProfile(
            pkg         = "com.ludo.king",
            displayName = "Ludo King",
            aliases     = listOf("ludo", "ludo king")
        ),
        AppProfile(
            pkg         = "com.activision.callofduty.shooter",
            displayName = "Call of Duty Mobile",
            aliases     = listOf("call of duty", "cod", "cod mobile")
        ),
        AppProfile(
            pkg         = "com.gameloft.android.ANMP.GloftA9HM",
            displayName = "Asphalt 9",
            aliases     = listOf("asphalt", "asphalt 9")
        ),
        AppProfile(
            pkg         = "com.ea.game.fifamobile_row",
            displayName = "EA FC Mobile",
            aliases     = listOf("ea fc", "fifa mobile", "ea sports fc")
        ),
        AppProfile(
            pkg         = "com.dreamplug.techno",
            displayName = "Dream11",
            aliases     = listOf("dream11", "dream 11")
        ),
        AppProfile(
            pkg         = "com.mplfantasysports.mpl",
            displayName = "MPL",
            aliases     = listOf("mpl", "mobile premier league")
        ),

        // ── Health & Fitness ──────────────────────────────────────────────────
        AppProfile(
            pkg         = "com.google.android.apps.fitness",
            displayName = "Google Fit",
            aliases     = listOf("google fit", "fit")
        ),
        AppProfile(
            pkg         = "com.nutrition.healthifyme",
            displayName = "HealthifyMe",
            aliases     = listOf("healthifyme", "healthify"),
            searchUri   = "https://healthifyme.com/food/search?q={q}"
        ),
        AppProfile(
            pkg         = "com.cure.fit",
            displayName = "Cult.fit",
            aliases     = listOf("cult fit", "cult.fit", "curefit")
        ),
        AppProfile(
            pkg         = "com.strava",
            displayName = "Strava",
            aliases     = listOf("strava"),
            searchUri   = "https://www.strava.com/athletes/search?text={q}"
        )
    )

    // Navigate URIs for cab apps — stored separately, not in AppProfile constructor
    private val navigateUris = mapOf(
        "com.ubercab"        to "uber://?action=setPickup&pickup=my_location&dropoff[formatted_address]={q}",
        "com.olacabs.customer" to "ola://booking?dropLat=0&dropLng=0&dropName={q}"
    )

    // ═════════════════════════════════════════════════════════════════════════
    //  Initialisation
    // ═════════════════════════════════════════════════════════════════════════

    /** Call once from AxiomService.onCreate(). Safe to call again to refresh. */
    fun init(context: Context) {
        val appCtx = context.applicationContext
        rebuildIndex(appCtx)
        registerPackageReceiver(appCtx)
        Log.i(TAG, "Registry ready: ${installedProfiles.size} known + ${dynamicSearchApps.size} dynamic apps")
    }

    private fun rebuildIndex(context: Context) {
        val pm = context.packageManager
        installedProfiles.clear()
        aliasIndex.clear()
        dynamicSearchApps.clear()

        val knownPackages = mutableSetOf<String>()

        // ── Layer 1: static database ──────────────────────────────────────────
        for (profile in KNOWN_APPS) {
            knownPackages += profile.pkg
            val installed = try {
                pm.getApplicationInfo(profile.pkg, 0)
                true
            } catch (e: PackageManager.NameNotFoundException) { false }

            if (installed) {
                val p = profile.copy(isInstalled = true)
                installedProfiles[p.pkg] = p
                for (alias in p.aliases) {
                    aliasIndex[alias.lowercase()] = p.pkg
                }
                // Also index display name directly
                aliasIndex[p.displayName.lowercase()] = p.pkg
            }
        }

        // ── Layer 2: dynamic scan — every other installed app ─────────────────
        try {
            val allApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            for (appInfo in allApps) {
                val pkg = appInfo.packageName
                if (pkg in knownPackages) continue

                // Skip system packages with no launcher icon
                val launchIntent = pm.getLaunchIntentForPackage(pkg) ?: continue

                val label = pm.getApplicationLabel(appInfo).toString()
                val labelLower = label.lowercase()
                var supportsSearch = false
                var supportsSend   = false

                // Check ACTION_SEARCH capability
                val searchIntent = Intent(Intent.ACTION_SEARCH).apply { `package` = pkg }
                if (pm.resolveActivity(searchIntent, 0) != null) {
                    supportsSearch = true
                    dynamicSearchApps[pkg] = label
                }

                // Check ACTION_SEND (text/plain) capability
                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    `package` = pkg
                }
                if (pm.resolveActivity(sendIntent, 0) != null) {
                    supportsSend = true
                }

                if (supportsSearch || supportsSend) {
                    val dynProfile = AppProfile(
                        pkg           = pkg,
                        displayName   = label,
                        aliases       = listOf(labelLower),
                        supportsSearch = supportsSearch,
                        supportsSend   = supportsSend,
                        isInstalled   = true
                    )
                    installedProfiles[pkg] = dynProfile
                    aliasIndex[labelLower] = pkg
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Dynamic scan error: ${e.message}")
        }
    }

    /** Listen for app installs/uninstalls and refresh automatically. */
    private var packageReceiverRegistered = false
    private fun registerPackageReceiver(appCtx: Context) {
        if (packageReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        // appCtx is applicationContext — safe to capture in a BroadcastReceiver
        appCtx.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                Log.d(TAG, "Package changed — rebuilding app index")
                val receiverCtx = c?.applicationContext ?: appCtx
                rebuildIndex(receiverCtx)
            }
        }, filter)
        packageReceiverRegistered = true
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Lookup API
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Find an installed AppProfile by voice input (fuzzy alias match).
     * "yt" → YouTube, "insta" → Instagram, "saavn" → JioSaavn
     */
    fun findApp(namePart: String): AppProfile? {
        val q = namePart.lowercase().trim()
        // Exact alias match first
        val exactPkg = aliasIndex[q]
        if (exactPkg != null) return installedProfiles[exactPkg]
        // Partial match — alias starts with query (handles "you" → "youtube")
        val partial = aliasIndex.entries
            .filter { it.key.startsWith(q) && q.length >= 3 }
            .mapNotNull { installedProfiles[it.value] }
            .firstOrNull()
        if (partial != null) return partial
        // Partial match — query contains alias (handles "youtube music" contains "youtube")
        // Pick the entry whose alias is longest (most specific match)
        val containsEntry = aliasIndex.entries
            .filter { e -> q.contains(e.key) && e.key.length >= 4 }
            .maxByOrNull { e -> e.key.length }
        if (containsEntry != null) return installedProfiles[containsEntry.value]
        return null
    }

    /** Returns all installed profiles for UI listing. */
    fun findInstalledApps(): List<AppProfile> = installedProfiles.values
        .filter { it.isInstalled }
        .sortedBy { it.displayName }

    /**
     * Build the best Intent for a search action on this app.
     * Falls back to ACTION_SEARCH → launch in order.
     */
    fun buildSearchIntent(context: Context, profile: AppProfile, query: String): Intent? {
        val enc = java.net.URLEncoder.encode(query, "UTF-8")

        // Use curated URI if available
        profile.searchUri?.let { uriTemplate ->
            val uri = uriTemplate.replace("{q}", enc)
            return buildViewIntent(uri, if (!uri.startsWith("http")) profile.pkg else null)
        }

        // Dynamic: try ACTION_SEARCH
        if (profile.supportsSearch) {
            return Intent(Intent.ACTION_SEARCH).apply {
                `package`  = profile.pkg
                putExtra("query", query)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }

        // Last resort: just launch
        return context.packageManager.getLaunchIntentForPackage(profile.pkg)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /**
     * Build the best Intent for a messaging action.
     * phoneNumber is the resolved E.164 number (can be null for apps like Telegram).
     */
    fun buildMessageIntent(context: Context, profile: AppProfile, phoneNumber: String?, text: String = ""): Intent? {
        val enc = java.net.URLEncoder.encode(text, "UTF-8")
        profile.messageUri?.let { template ->
            val number = phoneNumber ?: ""
            val uri = template.replace("{number}", number).replace("{q}", enc).replace("{msg}", enc)
            return buildViewIntent(uri, null)
        }
        // Generic SMS fallback
        if (!phoneNumber.isNullOrBlank()) {
            return Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$phoneNumber")).apply {
                putExtra("sms_body", text)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        return context.packageManager.getLaunchIntentForPackage(profile.pkg)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** Build the best Intent for an audio/video call. */
    fun buildCallIntent(context: Context, profile: AppProfile, phoneNumber: String?, isVideo: Boolean = false): Intent? {
        val number = phoneNumber ?: ""
        val uriTemplate = if (isVideo) profile.videoCallUri ?: profile.callUri
        else profile.callUri
        uriTemplate?.let {
            val uri = it.replace("{number}", number)
            return buildViewIntent(uri, null)
        }
        // Fallback: system phone call
        if (!number.isBlank()) {
            return Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        return context.packageManager.getLaunchIntentForPackage(profile.pkg)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** Build an Intent for navigation (Uber, Ola, etc.). */
    fun buildNavigateIntent(context: Context, profile: AppProfile, destination: String): Intent? {
        val enc = java.net.URLEncoder.encode(destination, "UTF-8")
        val navUri = navigateUris[profile.pkg]
        navUri?.let {
            return buildViewIntent(it.replace("{q}", enc), null)
        }
        return context.packageManager.getLaunchIntentForPackage(profile.pkg)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** Build a play/watch Intent (Spotify, Netflix, YouTube, etc.). */
    fun buildPlayIntent(context: Context, profile: AppProfile, query: String): Intent? {
        val enc = java.net.URLEncoder.encode(query, "UTF-8")
        val uriTemplate = profile.playUri ?: profile.watchUri ?: profile.searchUri
        uriTemplate?.let {
            return buildViewIntent(it.replace("{q}", enc),
                if (!it.startsWith("http")) profile.pkg else null)
        }
        return buildSearchIntent(context, profile, query)
    }

    // ── Internal ──────────────────────────────────────────────────────────────
    private fun buildViewIntent(uri: String, forcePkg: String?): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
            forcePkg?.let { `package` = it }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
}