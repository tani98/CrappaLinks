package com.germainz.crappalinks;

import android.app.Activity;
import android.app.AndroidAppHelper;
import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.XModuleResources;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.AsyncTask;
import android.text.ClipboardManager;
import android.view.View;
import android.widget.Toast;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;

import static de.robv.android.xposed.XposedHelpers.callMethod;
import static de.robv.android.xposed.XposedHelpers.findAndHookMethod;
import static de.robv.android.xposed.XposedHelpers.findClass;
import static de.robv.android.xposed.XposedHelpers.getObjectField;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class CrappaLinks implements IXposedHookLoadPackage, IXposedHookZygoteInit {

    final static ComponentName cn = new ComponentName("com.germainz.crappalinks", "com.germainz.crappalinks.Dummy");
    // see the comments in Dummy.java for more info on that.
    XSharedPreferences prefs = new XSharedPreferences("com.germainz.crappalinks");
    final String pref_toast_type = prefs.getString("pref_toast_type", "0");
    final boolean pref_unshorten_urls = prefs.getBoolean("pref_unshorten_urls", true);
    private static XModuleResources mResources;
    private static String MODULE_PATH = null;
    private static String toast_message_started;
    private static String toast_message_done;
    private static String toast_message_network;

    public void initZygote(StartupParam startupParam) throws Throwable {
        MODULE_PATH = startupParam.modulePath;
        mResources = XModuleResources.createInstance(MODULE_PATH, null);
        toast_message_network = mResources.getString(R.string.toast_message_network);
        toast_message_done = mResources.getString(R.string.toast_message_done);
        toast_message_started = mResources.getString(R.string.toast_message_started);
    }

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable {
        String pkg = lpparam.packageName;
        if (pkg.equals("com.quoord.tapatalkpro.activity") ||
                pkg.equals("com.quoord.tapatalkHD") ||
                pkg.equals("com.quoord.tapatalkxdapre.activity")) {
            final Class<?> TagHandler = findClass("com.quoord.tapatalkpro.adapter.forum.MessageContentAdapter", lpparam.classLoader);
            // Not sure when openUrlBySkimlink/doSkimlik are called instead of openUrlByVglink/doVglink,
            // it's never happened with me but better safe than sorry. Both methods take the same
            // argument so we can replace them with the same method.
            if (pkg.equals("com.quoord.tapatalkxdapre.activity")) {
                hookCrappaTalk(TagHandler, "openUrlBySkimlink");
                hookCrappaTalk(TagHandler, "openUrlByViglink");
            } else {
                hookCrappaTalk(TagHandler, "doVglink");
                hookCrappaTalk(TagHandler, "doSkimlik");
            }
        } else if (pkg.equals("com.android.vending")) {
            final Class<?> TagHandler = findClass("com.google.android.finsky.activities.DetailsTextViewBinder$SelfishUrlSpan", lpparam.classLoader);
            findAndHookMethod(TagHandler, "onClick", View.class, new XC_MethodHook() {
                // This method checks if the Play Store can handle the link. If it can, it'll open
                // it inside the Play Store. Otherwise, it'll send an intent for it.
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    Context context = ((View) param.args[0]).getContext();
                    Intent intent = new Intent("android.intent.action.VIEW");
                    String s = (String) callMethod(param.thisObject, "getURL");
                    Uri uri = Uri.parse(s);
                    s = uri.getQueryParameter("q");
                    intent.setData(uri);
                    intent.setPackage(context.getPackageName());
                    if (context.getPackageManager().resolveActivity(intent, 0x10000) != null)
                        return;
                    if (getRedirect(Uri.parse(s))) {
                        param.setResult(null);
                        return;
                    }
                    intent.setPackage(null);
                    context.startActivity(intent);
                    return;
                }
            });
        } else if (pkg.equals("com.facebook.katana")) {
            final Class<?> TagHandler = findClass("com.facebook.katana.urimap.Fb4aUriIntentMapper", lpparam.classLoader);
            final Class<?> TagHandler2 = findClass("com.facebook.intent.ufiservices.DefaultUriIntentGenerator", lpparam.classLoader);
            findAndHookMethod(TagHandler, "a", Context.class, String.class, new XC_MethodHook() {
                // This method returns an intent generated from the masked uri, if the Facebook app
                // can't handle it (external links.)
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    String s = (String) param.args[1];
                    s = unmaskFacebook(s);
                    Uri uri = Uri.parse(s);
                    if (getRedirect(uri))
                        param.setResult(new Intent().setData(uri).setComponent(cn));
                    else if (!s.equals(uri.toString()))
                        param.setResult(new Intent("android.intent.action.VIEW").setData(uri));
                }
            });
            findAndHookMethod(TagHandler2, "a", String.class, new XC_MethodReplacement() {
                // This method returns an intent to view the given URL, without doing any checks.
                // So we just have to alter the given argument.
                @Override
                protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                    String s = unmaskFacebook((String) param.args[0]);
                    Uri uri = Uri.parse(s);
                    if (getRedirect(uri)) {
                        Intent intent = new Intent("android.intent.action.VIEW").setData(uri);
                        return intent.setComponent(cn);
                    }
                    return new Intent("android.intent.action.VIEW").setData(uri);
                }
            });
        } else if (pkg.equals("com.vkontakte.android")) {
            final Class<?> TagHandler = findClass("com.vkontakte.android.LinkRedirActivity", lpparam.classLoader);
            findAndHookMethod(TagHandler, "openBrowser", Uri.class, new XC_MethodReplacement() {
                // This method takes one argument, the unmasked link, and generates an intent for it.
                @Override
                protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                    Uri uri = (Uri) param.args[0];
                    if (getRedirect(uri))
                        return (null);
                    Intent intent;
                    intent = new Intent("com.germainz.crappalinks.DUMMY", uri);
                    intent = intent.setPackage("com.germainz.crappalinks");
                    intent = intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    Application app = AndroidAppHelper.currentApplication();
                    try {
                        app.startActivity(intent);
                    } catch (Exception exception) {
                        exception.printStackTrace();
                    }
                    return null;
                }
            });
        } else if (pkg.equals("com.alphascope")) {
            final Class<?> TagHandler = findClass("com.alphascope.lib.util.ViglinkLinkify", lpparam.classLoader);
            findAndHookMethod(TagHandler, "getVigLinkURL", String.class, new XC_MethodReplacement() {
                // This method takes one argument (the unmasked link,) masks it, then returns the masked URL.
                @Override
                protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                    return param.args[0];
                }
            });
        } else if (pkg.equals("com.tippingcanoe.mydealz")) {
            final Class<?> TagHandler = findClass("com.tippingcanoe.mydealz.fragments.ShowDealFragment$9", lpparam.classLoader);
            findAndHookMethod(TagHandler, "onClick", View.class, new XC_MethodHook() {
                // This method does the following:
                // 1- track the click (not sure what this does; I'm guessing it's for a history feature?)
                // 2- copies the deal code to the clipboard if one exists
                // 3- generates the intent
                // Our replacement method ignore 1- but does 2- and executes ResolveUrl which will
                // do 3- after resolving the URL.
                @SuppressWarnings("deprecation")
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (!pref_unshorten_urls)
                        return;
                    Object ShowDealFragment = getObjectField(param.thisObject, "this$0");
                    Object dealToShow = getObjectField(ShowDealFragment, "dealToShow");
                    String dealLink = (String) callMethod(dealToShow, "getDealLink");
                    Boolean hasCode = (Boolean) callMethod(dealToShow, "hasCode");
                    String dealCode = (String) callMethod(dealToShow, "getCode");
                    if (hasCode) {
                        Application context = AndroidAppHelper.currentApplication();
                        ((ClipboardManager) context.getSystemService("clipboard")).setText(dealCode);
                        Toast.makeText(context, "Kopiert!!", 0).show();
                    }
                    Uri uri = Uri.parse(dealLink);
                    new ResolveUrl().execute(uri.toString());
                    param.setResult(null);
                }
            });
        }
    }

    private String unmaskFacebook(String s) {
        // masked links look like (http|https)://m.facebook.com/l.php?u=<actual_link>&h=<some_token>
        // internal links begin with fb://
        Uri uri = Uri.parse(s);
        String host = uri.getHost();
        if (s.startsWith("fb://") || !host.equals("m.facebook.com"))
            return s; // it's not an external link, we shouldn't mess with it
        s = uri.getQueryParameter("u");
        try {
            s = URLDecoder.decode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }
        return s;
    }

    private void hookCrappaTalk(final Class<?> TagHandler, String method) {
        findAndHookMethod(TagHandler, method, String.class, new XC_MethodReplacement() {
            // These methods all take the unmasked link as their argument, and launch and intent to
            // view it after masking it.
            @Override
            protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                String s = (String) param.args[0]; // this is the original URL
                Uri uri = Uri.parse(s);
                if (getRedirect(uri))
                    return null;
                Activity mContext = (Activity) getObjectField(param.thisObject, "mContext");
                Intent intent;
                intent = new Intent("android.intent.action.VIEW", uri);
                try {
                    mContext.startActivity(intent);
                } catch (Exception exception) {
                    exception.printStackTrace();
                }
                return null;
            }
        });
    }

    public static String redirectHosts[] = {"t.co", "youtu.be", "bit.ly", "menea.me", "kcy.me", "goo.gl", "ow.ly",
            "j.mp", "redes.li", "dlvr.it", "tinyurl.com", "tmblr.co", "reut.rs", "sns.mx", "wp.me", "4sq.com",
            "ed.cl", "huff.to", "mun.do", "cos.as", "flip.it", "amzn.to", "cort.as", "on.cnn.com", "fb.me",
            "shar.es", "spr.ly", "v.ht", "v0v.in", "redd.it", "bitly.com", "tl.gd", "wh.gov", "hukd.mydealz.de",
            "untp.i", "kck.st", "engt.co", "nyti.ms", "cnnmon.ie", "vrge.co", "is.gd", "cnn.it"};

    private boolean getRedirect(Uri uri) throws IOException {
        // Checks if the URL is a known URL shortener. If so, launch the URL resolver in the
        // background (AsyncTask) and return true, indicating we should try to stop the app we've
        // hooked from launching an intent so that we can later send our own (otherwise, two intents
        // would be fired, and the user would see two URLs open - the shortened one, then the unshortened
        // one)
        if (pref_unshorten_urls) {
            String host = uri.getHost();
            int i = redirectHosts.length;
            int j = 0;
            while (j < i) {
                if (host.equals(redirectHosts[j])) {
                    new ResolveUrl().execute(uri.toString());
                    return true;
                }
                j++;
            }
        }
        return false;
    }


    class ResolveUrl extends AsyncTask<String, Void, String> {
        Application context = null;

        protected ResolveUrl() {
            context = AndroidAppHelper.currentApplication();
        }

        @Override
        protected void onPreExecute() {
            if (!pref_toast_type.equals("0"))
                Toast.makeText(context, toast_message_started, Toast.LENGTH_SHORT).show();
        }

        protected String getRedirect(String url) {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setRequestProperty("User-Agent", "com.germainz.crappalinks");
                c.setRequestProperty("Accept-Encoding", "identity");
                c.setConnectTimeout(10000);
                c.setReadTimeout(15000);
                c.setInstanceFollowRedirects(false);
                c.connect();
                final int responseCode = c.getResponseCode();
                if (responseCode >= 300 && responseCode < 400) {
                    return c.getHeaderField("Location");
                } else if (c.getURL().getHost().equals("hukd.mydealz.de")) {
                    Document d = Jsoup.parse(c.getInputStream(), "UTF-8", url);
                    Elements refresh = d.select("meta[http-equiv=Refresh]");
                    if (!refresh.isEmpty())
                        return refresh.first().attr("url");
                }
            } catch (Exception e) {
                e.printStackTrace();
                return null;
            } finally {
                if (c != null)
                    c.disconnect();
            }
            return null;
        }

        protected String doInBackground(String... urls) {
            String redirectUrl = urls[0];
            String finalUrl = redirectUrl;
            if (((ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE)).getActiveNetworkInfo() == null)
                return null;
            while (redirectUrl != null) {
                redirectUrl = getRedirect(redirectUrl);
                if (redirectUrl != null)
                    finalUrl = redirectUrl;
            }
            return finalUrl;
        }

        protected void onPostExecute(String uri) {
            if (uri == null) {
                Toast.makeText(context, toast_message_network + uri, Toast.LENGTH_LONG).show();
                return;
            }
            if (pref_toast_type.equals("2"))
                Toast.makeText(context, toast_message_done + uri, Toast.LENGTH_LONG).show();
            Intent intent;
            intent = new Intent("android.intent.action.VIEW", Uri.parse(uri));
            intent = intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                context.startActivity(intent);
            } catch (Exception exception) {
                exception.printStackTrace();
            }
        }
    }

}

