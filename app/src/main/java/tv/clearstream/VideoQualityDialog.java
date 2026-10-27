package tv.clearstream;
import android.app.Activity;
import android.app.AlertDialog;
/** Report the video's decoded dimensions, never the screen size as stream quality. */
final class VideoQualityDialog {
    private VideoQualityDialog(){}
    static void show(Activity owner,int width,int height,Runnable originalControls){
        String current=width>0&&height>0?"当前视频："+width+"×"+height:"暂未读到视频分辨率，请先开始播放";
        new AlertDialog.Builder(owner).setTitle("视频画质").setMessage(current+"\n\n全屏只放大画面，保留原视频比例和分辨率。\n如果放大后模糊，请选择网站提供的更高清晰度，或使用更清晰的源。")
            .setPositiveButton("原站选择画质",(d,w)->originalControls.run()).setNegativeButton("继续观看",null).show();
    }
}
