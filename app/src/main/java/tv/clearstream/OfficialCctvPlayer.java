package tv.clearstream;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONObject;

/** Foreground loading / recovery UI shared by both official website engines. */
final class OfficialCctvPlayer {
    private final Activity activity;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final LinearLayout card,actions;
    private final TextView message;
    private boolean foreground,playing,failed;
    private double lastTime=-1;
    private final Runnable deadline=()->failure("暂时未能开始播放\n请重试，或返回选择其他频道");
    private final Runnable recoveryFocus=this::focusRecovery;
    private android.view.ViewTreeObserver.OnWindowFocusChangeListener recoveryWindowFocus;
    OfficialCctvPlayer(Activity owner,FrameLayout host,Runnable retry,Runnable back){
        activity=owner;
        card=new LinearLayout(owner);card.setOrientation(LinearLayout.VERTICAL);card.setGravity(Gravity.CENTER);card.setPadding(dp(24),dp(20),dp(24),dp(20));
        GradientDrawable bg=new GradientDrawable();bg.setColor(0xe6101418);bg.setCornerRadius(dp(14));card.setBackground(bg);
        message=new TextView(owner);message.setGravity(Gravity.CENTER);message.setTextColor(Color.WHITE);message.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,dp(25));card.addView(message,new LinearLayout.LayoutParams(-1,-2));
        actions=new LinearLayout(owner);actions.setGravity(Gravity.CENTER);LinearLayout.LayoutParams ap=new LinearLayout.LayoutParams(-1,dp(60));ap.topMargin=dp(20);card.addView(actions,ap);
        button("重试",retry);button("返回选台",back);
        FrameLayout.LayoutParams params=new FrameLayout.LayoutParams(dp(570),-2,Gravity.CENTER);host.addView(card,params);
        actions.addOnLayoutChangeListener((v,left,top,right,bottom,oldLeft,oldTop,oldRight,oldBottom)->{if(right>left&&bottom>top)actions.post(recoveryFocus);});
        recoveryWindowFocus=hasFocus->{if(hasFocus)actions.post(recoveryFocus);};card.getViewTreeObserver().addOnWindowFocusChangeListener(recoveryWindowFocus);loading();
    }
    private int dp(int value){android.util.DisplayMetrics m=activity.getResources().getDisplayMetrics();float scale=Math.min(1f,Math.min(m.widthPixels/m.density/960f,m.heightPixels/m.density/540f));return Math.round(value*m.density*scale);}
    private void button(String label,Runnable action){TextView b=new TextView(activity);b.setText(label);b.setTextColor(Color.WHITE);b.setGravity(Gravity.CENTER);b.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,dp(22));b.setFocusable(true);b.setFocusableInTouchMode(true);b.setOnFocusChangeListener((v,focus)->v.setBackgroundColor(focus?0xff27578c:0xff27313f));b.setBackgroundColor(0xff27313f);b.setOnClickListener(v->action.run());LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-1,1);p.setMargins(dp(8),0,dp(8),0);actions.addView(b,p);}
    void loading(){playing=false;failed=false;lastTime=-1;message.setText("正在连接央视官网\n请稍候，节目会自动开始");actions.setVisibility(View.GONE);card.setVisibility(View.VISIBLE);main.removeCallbacks(deadline);if(foreground)main.postDelayed(deadline,30000);}
    void foreground(boolean value){foreground=value;main.removeCallbacks(deadline);actions.removeCallbacks(recoveryFocus);if(value&&failed)actions.post(recoveryFocus);else if(value&&!playing)main.postDelayed(deadline,30000);}
    void sample(JSONObject state){
        if(!foreground)return;
        if(state.optInt("error")>0){failure("央视官网暂时无法播放此节目\n请重试，或返回选择其他频道");return;}
        double time=state.optDouble("time",0);
        if(state.optBoolean("video")&&!state.optBoolean("paused",true)&&state.optInt("width")>0&&state.optInt("height")>0&&state.optInt("ready")>=2&&lastTime>=0&&time>lastTime+.1){playing=true;failed=false;card.setVisibility(View.GONE);main.removeCallbacks(deadline);}
        else if("paused".equals(state.optString("cctvStatus"))){playing=false;failed=false;main.removeCallbacks(deadline);message.setText("已暂停\n按确定继续播放");actions.setVisibility(View.GONE);card.setVisibility(View.VISIBLE);}
        lastTime=time;
    }
    void failure(String text){if(!foreground)return;failed=true;playing=false;main.removeCallbacks(deadline);message.setText(text);actions.setVisibility(View.VISIBLE);card.setVisibility(View.VISIBLE);actions.removeCallbacks(recoveryFocus);actions.post(recoveryFocus);}
    private void focusRecovery(){if(foreground&&failed&&!activity.isFinishing()&&activity.hasWindowFocus()&&actions.isShown()&&actions.getWidth()>0&&actions.getHeight()>0&&!card.hasFocus())actions.getChildAt(0).requestFocus();}
    void destroy(){main.removeCallbacksAndMessages(null);actions.removeCallbacks(recoveryFocus);android.view.ViewTreeObserver observer=card.getViewTreeObserver();if(observer.isAlive())observer.removeOnWindowFocusChangeListener(recoveryWindowFocus);}
}
