package org.neissa.camera;

import android.app.*;
import android.os.*;
import android.view.View.*;

public class MainActivity extends Activity 
{
	NeissaCamera nc;
    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
		String cameraId = (String)(getIntent().getExtras().get("CAMERA_ID"));
        
        FragmentTransaction q = getFragmentManager().beginTransaction();
		q.replace(R.id.container, nc=NeissaCamera.newInstance(cameraId));
		q.commit();
    }

	@Override
	public void onWindowFocusChanged(boolean hasFocus)
	{
		if(!hasFocus)
			finish();
		super.onWindowFocusChanged(hasFocus);
	}

	public void takePicture(android.view.View view)
	{
		nc.onClick(view);
	}
}
