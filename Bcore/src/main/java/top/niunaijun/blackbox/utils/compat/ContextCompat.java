package top.niunaijun.blackbox.utils.compat;

import android.content.Context;
import android.content.ContextWrapper;
import android.os.Build;

import reflection.android.app.ContextImpl;
import top.niunaijun.blackbox.BlackBoxCore;

/**
 * Created by Milk on 3/31/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶　つ０
 * しーＪ
 * 此处无Bug
 */
public class ContextCompat {
    public static final String TAG = "ContextFixer";

    public static void fix(Context context) {
        try {
            int deep = 0;
            while (context instanceof ContextWrapper) {
                context = ((ContextWrapper) context).getBaseContext();
                deep++;
                if (deep >= 10) {
                    return;
                }
            }
            ContextImpl.mPackageManager.set(context, null);
            try {
                context.getPackageManager();
            } catch (Throwable e) {
                e.printStackTrace();
            }

            // Keep Context.getPackageName() logical on API33: explicit intents
            // constructed with new Intent(context, Target.class) must name the
            // virtual package. System boundaries forward the actual host identity.
            if (Build.VERSION.SDK_INT < 33)
                ContextImpl.mBasePackageName.set(context, BlackBoxCore.getHostPkg());
            ContextImpl.mOpPackageName.set(context, BlackBoxCore.getHostPkg());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
