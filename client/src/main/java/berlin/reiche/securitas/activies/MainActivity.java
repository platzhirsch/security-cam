package berlin.reiche.securitas.activies;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Handler.Callback;
import android.os.Message;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.RelativeLayout.LayoutParams;
import android.widget.TextView;
import berlin.reiche.securitas.Client;
import berlin.reiche.securitas.ClientModel;
import berlin.reiche.securitas.ClientModel.State;
import berlin.reiche.securitas.Model;
import berlin.reiche.securitas.Protocol;
import berlin.reiche.securitas.R;
import berlin.reiche.securitas.controller.Controller;
import berlin.reiche.securitas.controller.GCMIntentService;
import berlin.reiche.securitas.util.Observer;
import berlin.reiche.securitas.util.Settings;

import com.google.android.gcm.GCMRegistrar;

public class MainActivity extends Activity implements Observer<Model<State>>,
		Callback {

	private static String TAG = MainActivity.class.getSimpleName();

	public static final String GCM_SENDER_ID = "958926895848";

	public ImageView snapshot;

	public Button detectionToggle;

	public TextView status;

	public RelativeLayout snapshotArea;

	public ProgressBar progress;

	public TextView headline;

	public TextView subtitle;

	private SharedPreferences settings;

	private ClientModel model;

	private Controller controller;

	private Handler handler;

	/**
	 * Whether all components are initialized and can be referenced.
	 */
	private boolean initialized;

	/**
	 * Whether the motion detection on the endpoint is running.
	 */

	/**
	 * Called when the activity is first created.
	 * 
	 * @param savedInstanceState
	 *            If the activity is being re-initialized after previously being
	 *            shut down then this Bundle contains the data it most recently
	 *            supplied in onSaveInstanceState(Bundle). <b>Note: Otherwise it
	 *            is null.</b>
	 */
	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		Log.i(TAG, "onCreate");
		setContentView(R.layout.main);
		initialize();
		updateSettings();

		if (getLastNonConfigurationInstance() != null) {
			Bitmap bitmap = (Bitmap) getLastNonConfigurationInstance();
			snapshot.setImageBitmap(bitmap);
		}

		if (savedInstanceState != null) {
			Log.i(TAG, "Restore saved instance state");
			String stateKey = getString(R.string.is_detection_active_key);
			String snapshotKey = getString(R.string.snapshot_key);

			boolean state = savedInstanceState.getBoolean(stateKey);
			Bitmap bitmap = savedInstanceState.getParcelable(snapshotKey);

			snapshot.setImageBitmap(bitmap);
			Client.restoreClientState(state);
		} else {
			// maybe the user hit the *Back* button with the motion detection
			// still activate on the server, hence restore the state
			Client.synchronizeServerStatus();
		}

	}

	@Override
	public Object onRetainNonConfigurationInstance() {
		super.onRetainNonConfigurationInstance();
		return ((BitmapDrawable) snapshot.getDrawable()).getBitmap();
	}

	@Override
	protected void onDestroy() {
		try {
			Client.getController().dispose();
		} catch (Throwable t) {
			Log.e(TAG, "Failed to destroy the controller", t);
		} finally {
			super.onDestroy();
		}
	}

	@Override
	public void onSaveInstanceState(Bundle savedInstanceState) {
		super.onSaveInstanceState(savedInstanceState);
		String stateKey = getString(R.string.is_detection_active_key);
		String snapshotKey = getString(R.string.snapshot_key);

		boolean state = Client.isMotionDetectionActive();
		savedInstanceState.putBoolean(stateKey, state);
		if (snapshot.getDrawable() != null) {
			Bitmap bitmap = ((BitmapDrawable) snapshot.getDrawable())
					.getBitmap();
			savedInstanceState.putParcelable(snapshotKey, bitmap);
		}
	}

	@Override
	public void onNewIntent(Intent intent) {

		if (intent.getExtras() != null) {
			String filename = intent.getExtras().getString("filename");
			Log.d(TAG, "filename " + filename);
			if (filename != null) {
				GCMIntentService.resetMotionsDetected(this);
				lockInterface();
				handler.sendEmptyMessage(Protocol.DOWNLOAD_SNAPSHOT.code);
			}
		}
	}

	@Override
	public void onResume() {
		super.onResume();
		Log.i(TAG, "onResume");
		updateSettings();

		int orientation = getResources().getConfiguration().orientation;
		if (orientation == Configuration.ORIENTATION_LANDSCAPE) {

			getWindow().setFlags(FLAG_FULLSCREEN, FLAG_FULLSCREEN);
			LayoutParams params = new LayoutParams(MATCH_PARENT, MATCH_PARENT);

			snapshotArea.setLayoutParams(params);
			headline.setVisibility(View.GONE);
			subtitle.setVisibility(View.GONE);
		} else {
			updateInterface();
		}
	}

	public void initialize() {

		if (!initialized) {
			model = Client.getModel();
			controller = Client.getController();

			model.addObserver(this);
			controller.addOutboxHandler(new Handler(this));
			handler = controller.getInboxHandler();

			snapshot = (ImageView) findViewById(R.id.snapshot);
			detectionToggle = (Button) findViewById(R.id.detection_toggle);
			status = (TextView) findViewById(R.id.errors);
			progress = (ProgressBar) findViewById(R.id.progress_bar);
			snapshotArea = (RelativeLayout) findViewById(R.id.snapshot_area);
			headline = (TextView) findViewById(R.id.headline);
			subtitle = (TextView) findViewById(R.id.subtitle);
			Client.activity = this;
			initialized = true;
		}
	}

	private void updateSettings() {
		settings = PreferenceManager.getDefaultSharedPreferences(this);
		String host = settings.getString(SettingsActivity.HOST, null);
		String port = settings.getString(SettingsActivity.PORT, null);
		String username = settings.getString(SettingsActivity.USER, null);
		String password = settings.getString(SettingsActivity.PASSWORD, null);

		if (!isConfigured()) {
			startSettingsActivity(true);
		} else {
			Client.endpoint = "http://" + host + ":" + port;
			Client.settings = new Settings(host, port, username, password);
			Log.i(TAG, "Updated endpoint to " + Client.endpoint);
			manageDeviceRegistration();
		}
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		MenuInflater inflater = getMenuInflater();
		inflater.inflate(R.menu.menu, menu);
		return true;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
		case R.id.settings:
			startSettingsActivity(false);
			break;
		}
		return true;
	}

	private void startSettingsActivity(boolean forceConfiguration) {
		Intent intent = new Intent(this, SettingsActivity.class);
		intent.putExtra(SettingsActivity.DISPLAY_INSTRUCTION,
				forceConfiguration);
		startActivity(intent);
	}

	private boolean isConfigured() {
		settings = PreferenceManager.getDefaultSharedPreferences(this);
		String host = settings.getString(SettingsActivity.HOST, "");
		String port = settings.getString(SettingsActivity.PORT, "");
		String username = settings.getString(SettingsActivity.USER, "");
		String password = settings.getString(SettingsActivity.PASSWORD, "");

		boolean configured = !host.equals("") && !port.equals("")
				&& !username.equals("") && !password.equals("");

		return configured;
	}

	public void toggleMotionDetection(View view) {
		status.setText(null);
		Handler handler = controller.getInboxHandler();

		State state = (State) model.getState();
		switch (state) {
		case IDLE:
			handler.sendEmptyMessage(Protocol.START_DETECTION.code);
			break;
		case DETECTING:
			handler.sendEmptyMessage(Protocol.STOP_DETECTION.code);
			break;
		default:
			throw new IllegalStateException();
		}
	}

	public void lockInterface() {
		detectionToggle.setEnabled(false);
		snapshot.setEnabled(false);
		ProgressBar progress = (ProgressBar) findViewById(R.id.progress_bar);
		progress.setVisibility(View.VISIBLE);
		snapshot.setVisibility(View.INVISIBLE);
	}

	/**
	 * Unlocks the interface because a request was carried out, independent
	 * whether it was successful or not.
	 * 
	 * Only make {@link ImageView} for snapshot visible again, if the motion
	 * detection is currently active.
	 */
	public void unlockInterface() {
		detectionToggle.setEnabled(true);
		snapshot.setEnabled(true);
		progress.setVisibility(View.INVISIBLE);

		if (Client.isMotionDetectionActive()) {
			snapshot.setVisibility(View.VISIBLE);
		}
	}

	/**
	 * Registers the device on the GCM service. If the device is already
	 * registered the cached registration ID will be used.
	 */
	public void manageDeviceRegistration() {
		GCMRegistrar.checkDevice(this);
		GCMRegistrar.checkManifest(this);
		final String id = GCMRegistrar.getRegistrationId(this);
		if (id.equals("")) {
			Log.i(TAG, "No device id yet, issue registration indent.");
			GCMRegistrar.register(this, GCM_SENDER_ID);
		} else if (!GCMRegistrar.isRegisteredOnServer(this)) {
			Client.registerDevice(id, this);
		}
	}

	// TODO: make use of it
	public boolean isNetworkAvailable() {
		ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
		NetworkInfo networkInfo = cm.getActiveNetworkInfo();
		if (networkInfo != null && networkInfo.isConnected()) {
			return true;
		}
		return false;
	}

	public void refreshSnapshot(View view) {
		handler.sendEmptyMessage(Protocol.DOWNLOAD_LATEST_SNAPSHOT.code);
	}

	/**
	 * After the application is restarted due to application pause, termination,
	 * etc.
	 */
	public void updateInterface() {

		if (Client.motionDetectionActive) {
			setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR);
			detectionToggle.setText(R.string.button_stop_detection);
			snapshot.setImageBitmap(model.getSnapshot());
		} else {
			setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_NOSENSOR);
			detectionToggle.setText(R.string.button_start_detection);
			snapshot.setVisibility(ImageView.INVISIBLE);
		}

		if (model.getState() == State.DETECTING) {
			handler.sendEmptyMessage(Protocol.DOWNLOAD_LATEST_SNAPSHOT.code);
		}

		if (!model.isRegisteredOnServer()) {
			GCMRegistrar.setRegisteredOnServer(this, false);
			manageDeviceRegistration();
			handler.sendEmptyMessage(Protocol.START_DETECTION.code);
		}

	}

	@Override
	public void update(Model<State> subject) {

		runOnUiThread(new Runnable() {
			@Override
			public void run() {
				updateInterface();
			}
		});
	}

	@Override
	public boolean handleMessage(Message msg) {
		// TODO Auto-generated method stub
		return false;
	}
}
