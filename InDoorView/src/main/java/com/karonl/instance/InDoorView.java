package com.karonl.instance;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PointF;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.VelocityTracker;
import android.view.View;
import android.widget.Scroller;

import com.karonl.instance.Adapter.BitAdapter;
import com.karonl.instance.Interface.BitBuffer;
import com.karonl.instance.Unit.PathUnit;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Created by karonl on 16/3/21.
 * 绘制图案的画板,可以直接通过xml生成对象,在没有设置canvas时绘制空白页,因此可以下载完图片再更新进去
 */
public class InDoorView extends SurfaceView implements SurfaceHolder.Callback, View
        .OnTouchListener {

    private static final float VELOCITY_MULTI = 1f;// 滑动速度加权，计算松手后移动距离
    private static final int FRAME_INTERVAL = 5;// 帧时间

    private static final int CLICK = 0;// 点击
    private static final int DRAG = 1;// 拖动
    private static final int ZOOM = 2;// 放大
    private int mStatus = 0;//状态
    private int mClick = 0;//状态
    private int mCScroll = 0; //插值
    private float mStartDistance; //初始距离
    private float mPicWidth, mPicHeight; //图案宽度,图案高度,实时状态
    private float screenWidth, screenHeight;
    private PointF mStartPoint = new PointF(); //下手点
    private float scale, scaleFirst; //放大倍数
    private float bx, by; //图案初始坐标
    private Canvas c = null;
    private Thread drawThread;//绘制线程
    private final SurfaceHolder surfaceHolder;
    private BitBuffer adapter;
    private boolean canPaint = false;

    private Scroller mScroller;
    private VelocityTracker mVelocityTracker;

    public InDoorView(Context context, AttributeSet attributeSet) {
        super(context, attributeSet);
        this.setOnTouchListener(this);
        getHolder().addCallback(this);
        surfaceHolder = getHolder();

        mScroller = new Scroller(context);
    }

    public void setAdapter(BitBuffer adapter) {
        this.adapter = adapter;
        //重新校准位置和放大倍数
        if (screenHeight > 0 && screenWidth > 0) setAdapterInit();

    }

    private void setAdapterInit() {
        adapter.setOnAdapterListener(new BitAdapter.AttrListener() {
            @Override
            public void onRefresh() {
                setScale(true);
                setPicInit();
            }
        });
    }

    private void setPicInit() {
        if (bx != 0 && by != 0) return;
        //图片初始状态
        mPicWidth = adapter.getBitBuffer().getWidth() * scale;
        mPicHeight = adapter.getBitBuffer().getHeight() * scale;
        //初始坐标
        bx = (screenWidth - mPicWidth) / 2;
        by = (screenHeight - mPicHeight) / 2;
    }

    private void setScale(boolean changeFirst) {
        float scaleWidth = screenWidth / adapter.getBitBuffer().getWidth();
        float scaleHeight = screenHeight / adapter.getBitBuffer().getHeight();
        scale = scaleWidth > scaleHeight ? scaleHeight : scaleWidth;
        if (changeFirst) scaleFirst = scale;
    }

    /**
     * 设置帧数事件监听
     */
    public FramesListener listener;

    public interface FramesListener {
        void onRefresh(float number);
    }

    public void onFramesListener(FramesListener listener) {
        this.listener = listener;
    }

    /**
     * 绘制图画
     */
    private void looperRun() {
        drawThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (_run) {
                    showBit();//绘制
                }
            }
        });
        drawThread.start();
    }


    /**
     * 绘制图画
     */
    private void showBit() {
        if (canPaint) {
            long startTime = System.currentTimeMillis();
            synchronized (surfaceHolder) {

                c = surfaceHolder.lockCanvas();
                if (c != null && adapter != null && adapter.getBitBuffer() != null) {
                    c.drawColor(Color.GRAY);
                    c.scale(scale, scale);
                    c.drawBitmap(adapter.getBitBuffer(), bx / scale, by / scale, new Paint());
                }
                try {
                    surfaceHolder.unlockCanvasAndPost(c);
                } catch (IllegalStateException e) { //已经释放
                    Log.e("error:", "" + e);
                }
            }
            long endTime = System.currentTimeMillis();

            /**
             * 计算出绘画一次更新的毫秒数
             * **/
            int diffTime = (int) (endTime - startTime);
            if (diffTime < FRAME_INTERVAL) { //default:18
                try {
                    Thread.sleep(FRAME_INTERVAL - diffTime);
                } catch (InterruptedException e) {
                    //e.printStackTrace();
                }
            }
            sendToInterface(System.currentTimeMillis() - startTime);//发送一次循环运行总时间
        } else {
            try {
                Thread.sleep(FRAME_INTERVAL);
            } catch (InterruptedException e) {
                //e.printStackTrace();
            }
            sendToInterface(1000f);//发送一次循环运行总时间
        }
    }

    //显示帧数
    private boolean sendAble = true;
    private ScheduledExecutorService scheduledThreadPool = Executors.newScheduledThreadPool(1);

    private void sendToInterface(final float diffTime) {
        if (listener != null && sendAble) {
            sendAble = false;
            scheduledThreadPool.schedule(new Runnable() {
                @Override
                public void run() {
                    listener.onRefresh(diffTime);
                    sendAble = true;
                }
            }, 500, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * 手势(放大)事件
     */
    private void zoomMap(MotionEvent event) {
        synchronized (InDoorView.class) {
            float newDist = spacing(event);
            float scale1 = newDist / mStartDistance;
            mStartDistance = newDist;
            float tmp = scale * scale1;//缩放了比例值
            if (tmp < scaleFirst * 3 && tmp > scaleFirst * 0.6) {//放大的倍数范围
                scale = tmp;
            } else {
                return;
            }
            mPicHeight *= scale1;//缩放了高宽
            mPicWidth *= scale1;
            float fx = (event.getX(1) - event.getX(0)) / 2 + event.getX(0);//中点坐标
            float fy = (event.getY(1) - event.getY(0)) / 2 + event.getY(0);
            float XIn = fx - bx;//获得中点在图中的坐标
            float YIn = fy - by;
            XIn *= scale1;//坐标根据图片缩放而变化
            YIn *= scale1;
            bx = fx - XIn;//左上角的坐标等于中点坐标加图中偏移的坐标
            by = fy - YIn;
        }
    }

    /**
     * 手势(拖动)事件
     */
    private void drawMap(MotionEvent event) {
        synchronized (InDoorView.class) {
            PointF currentPoint = new PointF();
            currentPoint.set(event.getX(), event.getY());
            int offsetX = (int) (currentPoint.x - mStartPoint.x);
            int offsetY = (int) (currentPoint.y - mStartPoint.y);
            mStartPoint = currentPoint;
            bx += offsetX;
            by += offsetY;
        }
    }

    /**
     * 设置点击区域事件监听器
     */
    public void setOnClickMapListener(onClickMapListener maplistener) {
        this.maplistener = maplistener;
    }

    public interface onClickMapListener {
        void onClick(PathUnit unit);
    }

    private onClickMapListener maplistener;

    private void clickMap(MotionEvent event) {
        if (adapter != null)
            for (PathUnit region : adapter.getPathUnit()) {
                if (region.region.contains((int) ((event.getX() - bx) / scale), (int) ((event
                        .getY() - by) / scale))) {
                    if (maplistener != null)
                        maplistener.onClick(region);
                }
            }
    }

    private int x, y;

    /**
     * 监听点击事件
     */
    @Override
    public boolean onTouch(View v, MotionEvent event) {
        if (adapter != null)
            switch (event.getAction() & MotionEvent.ACTION_MASK) {
                case MotionEvent.ACTION_DOWN:
                    x = (int) event.getX();
                    y = (int) event.getY();
                    mClick = CLICK;
                    mStartPoint.set(event.getX(), event.getY());
                    mStatus = DRAG;
                    canPaint = true;
                    break;
                case MotionEvent.ACTION_POINTER_DOWN:
                    float distance = spacing(event); //初始距离
                    if (distance > 5f) {
                        mStatus = ZOOM;
                        mStartDistance = distance;
                    }
                    canPaint = true;
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (Math.abs(x - event.getX()) < 3 || Math.abs(y - event.getY()) < 3) {
                        mClick = CLICK; // 防止手滑的误差
                    } else {
                        if (mStatus == DRAG) {
                            drawMap(event);
                            mClick = DRAG;

                            if (mVelocityTracker == null) {
                                mVelocityTracker = VelocityTracker.obtain();
                            }
                            mVelocityTracker.addMovement(event);

                        } else {
                            if (event.getPointerCount() == 1) return true;
                            zoomMap(event);
                            mClick = DRAG;
                        }
                    }
                    break;
                case MotionEvent.ACTION_UP:

                    if (mClick == CLICK) { //点击图案
                        clickMap(event);
                        canPaint = false;
                    } else {
                        //获得VelocityTracker对象，并且添加滑动对象
                        int dx = 0;
                        int dy = 0;
                        if (mVelocityTracker != null) {
                            mVelocityTracker.computeCurrentVelocity(100);
                            dx = (int) (mVelocityTracker.getXVelocity() * VELOCITY_MULTI);
                            dy = (int) (mVelocityTracker.getYVelocity() * VELOCITY_MULTI);
                        }
                        mScroller.startScroll((int) mStartPoint.x, (int) mStartPoint.y, dx, dy,
                                500);
                        invalidate(); //触发computeScroll
                        //回收VelocityTracker对象
                        if (mVelocityTracker != null) {
                            mVelocityTracker.clear();
                        }
                    }


                    break;
                case MotionEvent.ACTION_POINTER_UP:
                    break;
                default:
                    break;
            }
        return true;
    }

    @Override
    public void computeScroll() {
        //先判断mScroller滚动是否完成
        if (mScroller.computeScrollOffset() && mStatus == 1 && mClick == 1) {
            //Log.i("test","computeScroll "+mScroller.getCurrX()+" "+mScroller.getCurrY());
            //这里调用View的scrollTo()完成实际的滚动
            PointF currentPoint = new PointF();
            currentPoint.set(mScroller.getCurrX(), mScroller.getCurrY());
            int offsetX = (int) (currentPoint.x - mStartPoint.x);
            int offsetY = (int) (currentPoint.y - mStartPoint.y);
            mStartPoint = currentPoint;
            bx += offsetX;
            by += offsetY;
            postInvalidate(); //相当于递归computeScroll();目的是触发computeScroll
        } else if (mStatus == 1 && mClick == 1) {
            canPaint = false;
            Log.d("scroll", "finish");
        }
        super.computeScroll();
    }


    // 界面初始化
    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        screenWidth = this.getWidth();
        screenHeight = this.getHeight();
        if (adapter != null) setAdapterInit();
        stopThread(false);
        canPaint = true;//初始化绘制
        looperRun();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        stopThread(true);
        drawThread.interrupt();
        drawThread = null;
    }

    //获取距离运算
    private float spacing(MotionEvent event) {
        float x = event.getX(0) - event.getX(1);
        float y = event.getY(0) - event.getY(1);
        return (float) Math.sqrt(x * x + y * y);
    }

    //控制绘画线程
    private boolean _run = true;

    public void stopThread(boolean run) {
        this._run = !run;
    }
}
