package io.github.mio.autopickupisland;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Bundle;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;

final class IslandPublisher {
    static final String ACTION_CONFIRM_KEY = "miui.focus.action_mio_confirm_pickup";
    private static final String PIC_LOGO = "miui.focus.pic_mio_brand_logo";
    private static final String PIC_MODEL = "miui.focus.pic_mio_category_model";
    private static final String PIC_STICKER = "miui.focus.pic_mio_brand_sticker";
    private static final String CHANNEL_FALLBACK = "MioAutoPickup";

    private IslandPublisher() {
    }

    @SuppressLint({"MissingPermission", "NotificationPermission"})
    // Runs inside AICR, which owns the notification permission and user-facing channel.
    static void publish(Context context, PickupEvent event, ColorOsRecognizer.Result result)
            throws Exception {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        String channel = ensureChannel(context, manager);
        PendingIntent open = createOpenPendingIntent(context, event);
        String notificationTag = notificationTag(event);
        int notificationId = notificationId(event);
        PendingIntent confirm = createDismissPendingIntent(
                context, notificationTag, notificationId);

        Context module = ModuleAccess.context(context);
        Bitmap logoBitmap = loadBitmap(module, event.logo);
        Bitmap stickerBitmap = loadBitmap(module, event.sticker);
        String modelAsset = modelAsset(event, result);
        Bitmap modelBitmap = composeModel(loadBitmap(module, modelAsset), stickerBitmap);
        Bitmap aodBitmap = loadBitmap(module, aodAsset(event, modelAsset));
        Icon logo = icon(context, logoBitmap);
        Uri animatedUri = animatedAssetUri(modelAsset);
        boolean animated = animatedUri != null && canOpen(context, animatedUri);
        Icon model = animated
                ? Icon.createWithContentUri(animatedUri.toString())
                : icon(context, modelBitmap != null ? modelBitmap : logoBitmap);
        Icon sticker = icon(context, stickerBitmap != null ? stickerBitmap : logoBitmap);
        Icon aod = icon(context, aodBitmap != null ? aodBitmap : logoBitmap);

        Notification.Action openAction = new Notification.Action.Builder(
                logo, "确认取餐", confirm).build();
        openAction.getExtras().putString("icon_name", "action_confirm_pickup");

        Bundle pictures = new Bundle();
        pictures.putParcelable(PIC_LOGO, logo);
        pictures.putParcelable(PIC_MODEL, model);
        pictures.putParcelable(PIC_STICKER, sticker);
        pictures.putParcelable("miui.focus.ic_version_code_notify", logo);
        pictures.putParcelable("miui.focus.pic_ticker", logo);
        pictures.putParcelable("miui.focus.pic_aod", aod);

        Bundle actions = new Bundle();
        actions.putParcelable(ACTION_CONFIRM_KEY, openAction);

        Bundle extras = new Bundle();
        String focus = focusJson(event, result, animated);
        extras.putString("miui.focus.param", focus);
        extras.putBundle("miui.focus.pics", pictures);
        extras.putBundle("miui.focus.actions", actions);
        extras.putBoolean("mio.auto_pickup", true);
        extras.putString("mio.pickup.appId", event.appId);
        extras.putString("mio.pickup.path", event.openPath());

        int smallIcon = context.getApplicationInfo().icon;
        if (smallIcon == 0) smallIcon = android.R.drawable.stat_notify_more;
        String brand = event.brand.isEmpty() ? "取餐提醒" : event.brand;
        Notification.Builder builder = new Notification.Builder(context, channel)
                .setSmallIcon(smallIcon)
                .setContentTitle(brand + " · 取餐码 " + result.code)
                .setContentText("点击打开对应订单页面")
                .setTicker(brand + " 取餐码 " + result.code)
                .setCategory(Notification.CATEGORY_STATUS)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setPriority(Notification.PRIORITY_HIGH)
                .setOnlyAlertOnce(true)
                .setSound(null)
                .setAutoCancel(false)
                .setShowWhen(true)
                .setWhen(System.currentTimeMillis())
                .setTimeoutAfter(2 * 60 * 60_000L)
                .setContentIntent(open)
                .addAction(openAction)
                .setExtras(extras);
        if (logoBitmap != null) builder.setLargeIcon(logoBitmap);
        try {
            builder.setColor(Color.parseColor(event.cardColor));
        } catch (Exception ignored) {
        }
        Notification notification = builder.build();
        notification.contentIntent = open;
        notification.extras.putString("miui.focus.param", focus);
        notification.extras.putBundle("miui.focus.pics", pictures);
        notification.extras.putBundle("miui.focus.actions", actions);
        enableFloat(notification);

        manager.notify(notificationTag, notificationId, notification);
    }

    static PendingIntent createOpenPendingIntent(Context context, PickupEvent event) {
        if (event.sourceOpenIntent != null
                && event.sourcePackage.equals(event.sourceOpenIntent.getCreatorPackage())) {
            return event.sourceOpenIntent;
        }
        Intent intent;
        int kind;
        if (Constants.PKG_WECHAT.equals(event.sourcePackage) && !event.appId.isEmpty()) {
            try {
                return NativeWechatNavigation.create(context, event);
            } catch (Exception error) {
                throw new IllegalStateException("Cannot create authenticated WeChat navigation", error);
            }
        } else if (Constants.PKG_ALIPAY.equals(event.sourcePackage) && !event.appId.isEmpty()) {
            Uri.Builder uri = Uri.parse("alipays://platformapi/startapp").buildUpon()
                    .appendQueryParameter("appId", event.appId);
            if (!event.openPath().isEmpty()) uri.appendQueryParameter("page", event.openPath());
            intent = new Intent(Intent.ACTION_VIEW, uri.build()).setPackage(Constants.PKG_ALIPAY)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            kind = 2;
        } else {
            intent = standaloneIntent(context, event);
            kind = 2;
        }
        intent.setIdentifier("mio-pickup-" + event.navigationKey().substring(0, 16));
        int requestCode = 0x4d000000 | (event.navigationKey().hashCode() & 0x00ffffff);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        return kind == 1
                ? PendingIntent.getService(context, requestCode, intent, flags)
                : PendingIntent.getActivity(context, requestCode, intent, flags);
    }

    private static PendingIntent createDismissPendingIntent(Context context, String tag, int id) {
        Intent intent = new Intent(Constants.ACTION_DISMISS)
                .setComponent(new ComponentName(Constants.PKG_AICR,
                        Constants.AICR_WAKE_RECEIVER))
                .setIdentifier("mio-dismiss-pickup-" + Integer.toHexString(id))
                .putExtra(Constants.EXTRA_NOTIFICATION_TAG, tag)
                .putExtra(Constants.EXTRA_NOTIFICATION_ID, id);
        return PendingIntent.getBroadcast(context, id ^ 0x4d10,
                intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static String notificationTag(PickupEvent event) {
        return "mio.coloros.pickup." + event.sourcePackage + '.' + event.appId;
    }

    private static int notificationId(PickupEvent event) {
        return 0x4d490000
                | (Math.abs((event.appId + event.sourcePackage).hashCode()) & 0xffff);
    }

    private static Intent wechatOperationIntent(PickupEvent event) {
        JSONObject item = new JSONObject();
        JSONObject params = new JSONObject();
        try {
            item.put("type", "wechatMiniProgram");
            item.put("url", "");
            item.put("appId", event.appId);
            item.put("path", event.openPath());
            params.put("items", new JSONArray().put(item));
        } catch (Exception ignored) {
        }
        String trace = "mio-" + event.id();
        return new Intent(Constants.AICR_OPERATION_ACTION)
                .setComponent(new ComponentName(Constants.PKG_AICR,
                        Constants.AICR_OPERATION_SERVICE))
                .putExtra("function", "openApp")
                .putExtra("traceId", trace)
                .putExtra("instanceId", trace)
                .putExtra("from", "airecoNotification")
                .putExtra("params", params.toString())
                .putExtra("notificationData", event.toJson().toString());
    }

    private static Intent standaloneIntent(Context context, PickupEvent event) {
        String targetPackage = event.sourcePackage;
        String targetClass = event.launchPath;
        if (!targetClass.isEmpty() && targetClass.contains(".")) {
            return new Intent(Intent.ACTION_MAIN)
                    .setComponent(new ComponentName(targetPackage, targetClass))
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .putExtra("mio_from_island", true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        }
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(targetPackage);
        if (launch != null) return launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return new Intent(Intent.ACTION_MAIN).setPackage(targetPackage)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    private static String focusJson(PickupEvent event, ColorOsRecognizer.Result result,
                                    boolean animated) {
        String brand = event.brand.isEmpty() ? "取餐提醒" : event.brand;
        String highlight = PickupEvent.color(event.pickupColor, "#3482FF");
        String buttonColor = PickupEvent.color(event.pickupButtonColor, "#3482FF");
        try {
            JSONObject leftPic = new JSONObject().put("type", 3).put("pic", PIC_LOGO);
            JSONObject leftText = new JSONObject().put("title", brand)
                    .put("showHighlightColor", false);
            JSONObject left = new JSONObject().put("type", 1)
                    .put("picInfo", leftPic).put("textInfo", leftText);
            JSONObject right = new JSONObject().put("title", result.code)
                    .put("showHighlightColor", true);
            JSONObject big = new JSONObject().put("imageTextInfoLeft", left)
                    .put("textInfo", right)
                    .put("fixedWidthDigitInfo", new JSONObject()
                            .put("digit", result.code).put("content", "取餐码")
                            .put("showHighlightColor", true));
            JSONObject small = new JSONObject().put("type", 1).put("pic", PIC_LOGO);
            JSONObject island = new JSONObject()
                    .put("islandProperty", 1)
                    .put("islandTimeout", 7_200)
                    .put("highlightColor", highlight)
                    .put("bigIslandArea", big)
                    .put("smallIslandArea", small);

            String product = displayProduct(event, result);
            String detail = displayDetail(event, result, brand, product);
            JSONObject animIcon = new JSONObject()
                    .put("type", animated ? 3 : 0)
                    .put("src", PIC_MODEL)
                    .put("srcDark", PIC_MODEL);
            if (animated) {
                // Current Xiaomi renderer ignores these for iconTextInfo, but other OS3 builds
                // honor them. The source WebP itself is authored with a one-shot loop count.
                animIcon.put("autoplay", true).put("loop", false).put("number", 0);
            }
            JSONObject content = new JSONObject()
                    .put("title", result.code)
                    .put("content", product)
                    .put("subContent", detail)
                    .put("colorTitle", highlight)
                    .put("colorTitleDark", highlight)
                    .put("colorContent", "#1A1A1A")
                    .put("colorContentDark", "#FFFFFF")
                    .put("colorSubContent", "#858585")
                    .put("colorSubContentDark", "#A7A7A7")
                    .put("animIconInfo", animIcon);
            JSONObject action = new JSONObject()
                    .put("action", ACTION_CONFIRM_KEY)
                    .put("type", 2)
                    .put("actionTitle", "确认取餐")
                    .put("actionTitleColor", "#FFFFFF")
                    .put("actionTitleColorDark", "#FFFFFF")
                    .put("actionBgColor", buttonColor)
                    .put("actionBgColorDark", buttonColor)
                    .put("clickWithCollapse", true)
                    .put("actionIntentType", 2);
            JSONObject background = new JSONObject()
                    .put("type", 1)
                    .put("colorBg", translucentColor(event.cardColor, event.cardAlpha));
            JSONObject param = new JSONObject()
                    .put("protocol", 1)
                    .put("business", "verificationCode")
                    .put("updatable", true)
                    .put("enableFloat", true)
                    .put("islandFirstFloat", true)
                    .put("filterWhenNoPermission", false)
                    .put("reopen", "reopen")
                    .put("timeout", 120)
                    .put("ticker", brand + " 取餐码 " + result.code)
                    .put("tickerPic", PIC_LOGO)
                    .put("tickerPicDark", PIC_LOGO)
                    .put("aodTitle", brand + " " + result.code)
                    .put("aodPic", PIC_LOGO)
                    .put("param_island", island)
                    .put("iconTextInfo", content)
                    .put("actions", new JSONArray().put(action))
                    .put("bgInfo", background)
                    .put("extraInfo", new JSONObject()
                            .put("source", "ColorOS16-PCR")
                            .put("category", event.category)
                            .put("cardColor", event.cardColor)
                            .put("cardAlpha", event.cardAlpha)
                            .put("animatedModel", animated)
                            .put("sticker", PIC_STICKER));
            return new JSONObject().put("param_v2", param).toString();
        } catch (Exception ignored) {
            return "{}";
        }
    }

    static String modelAsset(PickupEvent event, ColorOsRecognizer.Result result) {
        if (!event.baseStyle.isEmpty()) return event.baseStyle;
        // Home-page recommendations/coupons are not ingredients of this order.
        String text = event.brand + ' ' + result.product;
        return switch (event.category) {
            case "coffee" -> coffeeStyle(text);
            case "catering" -> cateringStyle(text);
            default -> teaStyle(text);
        };
    }

    private static String teaStyle(String text) {
        if (containsAny(text, "杨枝甘露", "芒果", "鲜橙", "胡萝卜", "芒芒"))
            return "base_bg_tea_style_orange.webp";
        if (containsAny(text, "草莓", "红豆", "西瓜", "杨梅", "莓莓", "蔓越莓"))
            return "base_bg_tea_style_red.webp";
        if (containsAny(text, "青提", "抹茶", "猕猴桃", "牛油果", "青柠", "青芒",
                "甘蓝", "薄荷", "黄瓜", "青苹果", "玉菇", "羽衣"))
            return "base_bg_tea_style_green.webp";
        if (containsAny(text, "柠檬", "百香果", "百香", "凤梨", "菠萝"))
            return "base_bg_tea_style_yellow.webp";
        if (containsAny(text, "奶茶")) return "base_bg_tea_style_milk.webp";
        if (containsAny(text, "蜜桃", "桃桃", "鲜桃", "芭乐"))
            return "base_bg_tea_style_peach.webp";
        if (containsAny(text, "葡萄", "蓝莓", "黑加仑", "紫薯", "火龙果"))
            return "base_bg_tea_style_grap.webp";
        if (containsAny(text, "乌龙", "红袍")) return "base_bg_tea_style_oolong.webp";
        return "base_bg_tea_style_common_cold.webp";
    }

    private static String coffeeStyle(String text) {
        if (containsAny(text, "星冰乐")) return "base_bg_coffee_style_xingbake_xingbingle.webp";
        if (containsAny(text, "拿铁", "摩卡", "生椰")) return "base_bg_coffee_style_latte.webp";
        if (containsAny(text, "馥芮白", "焦糖", "卡布奇诺"))
            return "base_bg_coffee_style_milk.webp";
        if (containsAny(text, "星巴克") && !containsAny(text, "冰", "冷"))
            return "base_bg_coffee_style_xingbake_hot.webp";
        return "base_bg_coffee_style_common_cold.webp";
    }

    private static String cateringStyle(String text) {
        if (containsAny(text, "肯德基", "KFC")) return "base_bg_takeout_style_kendeji.webp";
        if (containsAny(text, "麦当劳")) return "base_bg_takeout_style_maidanglao.webp";
        if (containsAny(text, "塔斯汀")) return "base_bg_takeout_style_tustin.webp";
        return "base_bg_takeout_style_common.webp";
    }

    private static String aodAsset(PickupEvent event, String model) {
        if (!event.aodImage.isEmpty()) return event.aodImage;
        return model.replace("base_bg_tea_style_", "aod_static_tea_")
                .replace("base_bg_coffee_style_", "aod_static_coffee_")
                .replace("base_bg_takeout_style_", "aod_static_takeout_");
    }

    private static boolean containsAny(String text, String... needles) {
        for (String needle : needles) if (text.contains(needle)) return true;
        return false;
    }

    static String displayProduct(PickupEvent event, ColorOsRecognizer.Result result) {
        String product = compact(result.product);
        if (!product.isEmpty()) return PickupEvent.truncate(product, 32);
        // "商品直减" and "商品兑换券" are promotions, not fallback product names.
        // Product extraction belongs exclusively to the original PCR pipeline.
        return PickupEvent.truncate((event.brand.isEmpty() ? "取餐" : event.brand) + "订单", 32);
    }

    static String displayDetail(PickupEvent event, ColorOsRecognizer.Result result,
                                String brand, String product) {
        String[] lines = event.content.split("[\\r\\n]+");
        for (String raw : lines) {
            String line = compact(raw);
            String value = stripLabel(line, "取餐门店", "门店地址", "门店", "店铺", "地址");
            if (!value.equals(line) && usableDetail(value, result.code, product)) {
                return PickupEvent.truncate(value, 38);
            }
        }
        for (String raw : lines) {
            String line = compact(raw);
            if (usableDetail(line, result.code, product)
                    && containsAny(line, "店", "校区", "公寓", "广场", "商场", "中心", "路", "街")) {
                return PickupEvent.truncate(line, 38);
            }
        }
        String status = compact(result.status);
        return PickupEvent.truncate(status.isEmpty() ? brand + " · 点击查看订单详情"
                : brand + " · " + status, 38);
    }

    private static boolean usableDetail(String value, String code, String product) {
        return value.length() >= 2 && value.length() <= 80 && !value.equals(code)
                && !value.equals(product) && !value.contains("取餐码")
                && !value.contains("订单状态") && !value.contains("切换新码")
                && !value.contains("重复写入");
    }

    private static String stripLabel(String line, String... labels) {
        for (String label : labels) {
            if (line.startsWith(label)) {
                return compact(line.substring(label.length()).replaceFirst("^[：:·\\s]+", ""));
            }
        }
        return line;
    }

    private static String compact(String value) {
        return PickupEvent.clean(value).replaceAll("\\s+", " ");
    }

    private static String translucentColor(String color, float alpha) {
        try {
            int parsed = Color.parseColor(PickupEvent.color(color, "#3A3A3A"));
            int a = Math.round(PickupEvent.alpha(alpha, 0.12f) * 255f);
            return String.format(Locale.ROOT, "#%02X%02X%02X%02X", a,
                    Color.red(parsed), Color.green(parsed), Color.blue(parsed));
        } catch (RuntimeException ignored) {
            return "#1F3A3A3A";
        }
    }

    private static Uri animatedAssetUri(String relative) {
        String clean = PickupEvent.clean(relative).replace('\\', '/');
        if (!clean.matches("[a-z0-9_]{1,96}\\.webp") || !clean.startsWith("base_bg_")) {
            return null;
        }
        return Constants.PROVIDER_URI.buildUpon().appendPath("asset").appendPath(clean).build();
    }

    private static boolean canOpen(Context context, Uri uri) {
        try (AssetFileDescriptor ignored =
                     context.getContentResolver().openAssetFileDescriptor(uri, "r")) {
            return ignored != null && ignored.getLength() > 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Bitmap composeModel(Bitmap model, Bitmap sticker) {
        if (model == null || sticker == null || model.getWidth() < 40 || model.getHeight() < 40) {
            return model;
        }
        Bitmap output = Bitmap.createBitmap(model.getWidth(), model.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        canvas.drawBitmap(model, 0, 0, paint);
        float size = Math.min(model.getWidth(), model.getHeight()) * 0.24f;
        float left = (model.getWidth() - size) / 2f;
        float top = model.getHeight() * 0.51f - size / 2f;
        canvas.drawBitmap(sticker, null, new RectF(left, top, left + size, top + size), paint);
        return output;
    }

    private static Bitmap loadBitmap(Context module, String relative) {
        String clean = PickupEvent.clean(relative).replace('\\', '/');
        if (clean.isEmpty() || clean.contains("..")) return null;
        try (InputStream input = module.getAssets().open(Constants.IMAGE_ROOT + clean)) {
            return BitmapFactory.decodeStream(input);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Icon icon(Context context, Bitmap bitmap) {
        if (bitmap != null) return Icon.createWithBitmap(bitmap);
        int id = context.getApplicationInfo().icon;
        return Icon.createWithResource(context, id == 0 ? android.R.drawable.stat_notify_more : id);
    }

    private static String ensureChannel(Context context, NotificationManager manager) {
        String preferred = "ProactiveIntelligence";
        if (manager.getNotificationChannel(preferred) != null) return preferred;
        NotificationChannel channel = manager.getNotificationChannel(CHANNEL_FALLBACK);
        if (channel == null) {
            channel = new NotificationChannel(CHANNEL_FALLBACK, "自动取餐码",
                    NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("ColorOS 规则识别后的取餐码超级岛");
            channel.setSound(null, null);
            channel.enableVibration(false);
            manager.createNotificationChannel(channel);
        }
        return CHANNEL_FALLBACK;
    }

    private static void enableFloat(Notification notification) {
        try {
            Field field = Notification.class.getDeclaredField("extraNotification");
            field.setAccessible(true);
            Object extra = field.get(notification);
            if (extra == null) return;
            Method method = extra.getClass().getMethod("setEnableFloat", boolean.class);
            method.invoke(extra, true);
        } catch (Throwable ignored) {
        }
    }
}
