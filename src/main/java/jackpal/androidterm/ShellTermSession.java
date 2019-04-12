package jackpal.androidterm;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.widget.Toast;

import com.quseit.util.FileHelper;
import com.quseit.util.FileUtils;
import com.quseit.util.NAction;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;

import jackpal.androidterm.compat.FileCompat;
import jackpal.androidterm.emulatorview.ColorScheme;
import jackpal.androidterm.emulatorview.TermSession;
import jackpal.androidterm.emulatorview.UpdateCallback;
import jackpal.androidterm.util.NStorage;
import jackpal.androidterm.util.TermSettings;

/**
 * A terminal session, consisting of a TerminalEmulator, a TranscriptScreen,
 * the PID of the process attached to the session, and the I/O streams used to
 * talk to the process.
 */
public class ShellTermSession extends TermSession {
    //** Set to true to force into 80 x 24 for testing with vttest. */
    private static final boolean VTTEST_MODE = false;
    private TermSettings mSettings;
    private Context context;
    
    private int mProcId;
    private FileDescriptor mTermFd;
    private Thread mWatcherThread;

    // A cookie which uniquely identifies this session.
    private String mHandle;
    private String pyPath = "";

    private String mInitialCommand;
    private boolean isEnd = false;

    public static final int PROCESS_EXIT_FINISHES_SESSION = 0;
    public static final int PROCESS_EXIT_DISPLAYS_MESSAGE = 1;
    private int mProcessExitBehavior = PROCESS_EXIT_FINISHES_SESSION;

    private String mProcessExitMessage;

    private static final int PROCESS_EXITED = 1;
    
    
    @SuppressLint("HandlerLeak")
	private Handler mMsgHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            if (!isRunning()) {
            	//Log.d("TERM", "isRunning");
                return;
            }
            if (msg.what == PROCESS_EXITED) {
            	//Log.d("TERM", "PROCESS_EXITED");

                onProcessExit((Integer) msg.obj);
            }
        }
    };

    private UpdateCallback mUTF8ModeNotify = new UpdateCallback() {
        public void onUpdate() {
            Exec.setPtyUTF8Mode(mTermFd, getUTF8Mode());
        }
    };

    public boolean getEndStat() {
    	return this.isEnd;
    }
    
    public ShellTermSession(Context context, TermSettings settings, String cmd, String pyPath) {
        super();
        this.context = context;
        this.pyPath = pyPath;
        this.isEnd = false;
        
        updatePrefs(settings);

        initializeSession(cmd);
        this.mInitialCommand = cmd;

        mWatcherThread = new Thread() {
             @Override
             public void run() {
                Log.i(TermDebug.LOG_TAG, "waiting for: " + mProcId);
                int result = Exec.waitFor(mProcId);
                Log.i(TermDebug.LOG_TAG, "Subprocess exited: " + result);
                mMsgHandler.sendMessage(mMsgHandler.obtainMessage(PROCESS_EXITED, result));
                isEnd = true;
             }
        };
        mWatcherThread.setName("Process watcher");
        //Log.d(TermDebug.LOG_TAG, "ShellTermSession:"+cmd);
    }

    public void shellRun() {
        //Exec.setPtyUTF8Mode(mTermFd, getUTF8Mode());
        //setUTF8ModeUpdateCallback(mUTF8ModeNotify);
    	initializeEmulator(80,24);
        /*mWatcherThread.start();
        sendInitialCommand(mInitialCommand);*/

    }

    public void updatePrefs(TermSettings settings) {
        mSettings = settings;
        try {
            setColorScheme(new ColorScheme(settings.getColorScheme()));
            setDefaultUTF8Mode(settings.defaultToUTF8Mode());

        } catch (Exception e) {

        }
    }
    
    private void initializeSession(String cmd) {
        TermSettings settings = mSettings;

        int[] processId = new int[1];

        String path = System.getenv("PATH");
        if (settings.doPathExtensions()) {
            String appendPath = settings.getAppendPath();
            if (appendPath != null && appendPath.length() > 0) {
                path = path + ":" + appendPath;
            }

            if (settings.allowPathPrepend()) {
                String prependPath = settings.getPrependPath();
                if (prependPath != null && prependPath.length() > 0) {
                    path = prependPath + ":" + path;
                }
            }
        }
        if (settings.verifyPath()) {
            path = checkPath(path);
        }
        String[] env = new String[21];
        File filesDir = this.context.getFilesDir();

        env[0] = "TERM=" + settings.getTermType();
        env[1] = "PATH=" + this.context.getFilesDir()+"/bin"+":"+path;
        env[2] = "LD_LIBRARY_PATH=.:"+filesDir+"/lib/"+":"+filesDir+"/:"+filesDir.getParentFile()+"/lib/";
        env[3] = "PYTHONHOME="+filesDir;
        env[4] = "ANDROID_PRIVATE="+filesDir;
        

        // HACKED FOR QPython
        File externalStorage = new File(Environment.getExternalStorageDirectory(), "qpython");

        if (!externalStorage.exists()) {
        	externalStorage.mkdir();
        }

        String py3 = NAction.getQPyInterpreter(this.context);

        Log.d("HERE", py3);
        env[5] = "PYTHONPATH="
                +filesDir+"/lib/"+py3+"/site-packages/:"
                +filesDir+"/lib/"+py3+"/:"
                +filesDir+"/lib/"+py3.replace(".","")+".zip:"
                +filesDir+"/lib/"+py3+"/qpyutil.zip:"
                +filesDir+"/lib/"+py3+"/lib-dynload/:"
                +externalStorage+"/lib/"+py3+"/site-packages/:"
                +pyPath;

        env[14] = "IS_QPY3=1";

        
        env[6] = "PYTHONOPTIMIZE=2";
        env[7] = "TMPDIR="+externalStorage+"/cache";
        File td = new File(externalStorage+"/cache");
        if (!td.exists()) {
        	td.mkdir();
        }
        
        env[8] = "AP_HOST="+NStorage.getSP(this.context, "sl4a.hostname");
        env[9] = "AP_PORT="+NStorage.getSP(this.context, "sl4a.port");
        env[10] = "AP_HANDSHAKE="+NStorage.getSP(this.context, "sl4a.secue");

        env[11] = "ANDROID_PUBLIC="+externalStorage;
        env[12] = "ANDROID_PRIVATE="+this.context.getFilesDir().getAbsolutePath();
        env[13] = "ANDROID_ARGUMENT="+pyPath;

        env[15] = "QPY_USERNO="+NAction.getUserNoId(context);
        env[16] = "QPY_ARGUMENT="+NAction.getExtConf(context);
        env[17] = "PYTHONDONTWRITEBYTECODE=1";
        env[18] = "TMP="+externalStorage+"/cache";
        env[19] = "ANDROID_APP_PATH="+externalStorage+"";
        env[20] = "LANG=en_US.UTF-8";

        File enf = new File(context.getFilesDir()+"/bin/init.sh");
        //if (! enf.exists()) {
    	String content = "#!/system/bin/sh";
        for (int i=0;i<env.length;i++) {
        	content += "\nexport "+env[i];
        }
    	FileHelper.putFileContents(context, enf.getAbsolutePath(), content.trim());
		try {
			FileUtils.chmod(enf, 0755);
		}
		catch (Exception e) {
			e.printStackTrace();
		}

        //}
        //File libPath = context.getFilesDir();
        //loadLibrary(libPath);

        
        createSubprocess(processId, settings.getShell(), env);
        mProcId = processId[0];

        setTermOut(new FileOutputStream(mTermFd));
        setTermIn(new FileInputStream(mTermFd));
    }

    private String checkPath(String path) {
        String[] dirs = path.split(":");
        StringBuilder checkedPath = new StringBuilder(path.length());
        for (String dirname : dirs) {
            File dir = new File(dirname);
            if (dir.isDirectory() && FileCompat.canExecute(dir)) {
                checkedPath.append(dirname);
                checkedPath.append(":");
            }
        }
        return checkedPath.substring(0, checkedPath.length()-1);
    }

    @Override
    public void initializeEmulator(int columns, int rows) {
        if (VTTEST_MODE) {
            columns = 80;
            rows = 24;
        }
        super.initializeEmulator(columns, rows);

        Exec.setPtyUTF8Mode(mTermFd, getUTF8Mode());
        setUTF8ModeUpdateCallback(mUTF8ModeNotify);

        mWatcherThread.start();
        sendInitialCommand(mInitialCommand);
    }

    private void sendInitialCommand(String initialCommand) {
    	//Log.d("TERM", "sendInitialCommand:"+initialCommand);
        if (initialCommand.length() > 0) {
            write(initialCommand + '\r');
        }
    }

     private void createSubprocess(int[] processId, String shell, String[] env) {
        ArrayList<String> argList = parse(shell);
        String arg0;
        String[] args;

        try {
            arg0 = argList.get(0);
            File file = new File(arg0);
            if (!file.exists()) {
                Log.e(TermDebug.LOG_TAG, "Shell " + arg0 + " not found!");
                throw new FileNotFoundException(arg0);
            } else if (!FileCompat.canExecute(file)) {
                Log.e(TermDebug.LOG_TAG, "Shell " + arg0 + " not executable!");
                throw new FileNotFoundException(arg0);
            }
            args = argList.toArray(new String[1]);
        } catch (Exception e) {
            argList = parse(mSettings.getFailsafeShell());
            arg0 = argList.get(0);
            args = argList.toArray(new String[1]);
        }


        mTermFd = Exec.createSubprocess(arg0, args, env, processId);
    }

    private ArrayList<String> parse(String cmd) {
        final int PLAIN = 0;
        final int WHITESPACE = 1;
        final int INQUOTE = 2;
        int state = WHITESPACE;
        ArrayList<String> result =  new ArrayList<String>();
        int cmdLen = cmd.length();
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < cmdLen; i++) {
            char c = cmd.charAt(i);
            if (state == PLAIN) {
                if (Character.isWhitespace(c)) {
                    result.add(builder.toString());
                    builder.delete(0,builder.length());
                    state = WHITESPACE;
                } else if (c == '"') {
                    state = INQUOTE;
                } else {
                    builder.append(c);
                }
            } else if (state == WHITESPACE) {
                if (Character.isWhitespace(c)) {
                    // do nothing
                } else if (c == '"') {
                    state = INQUOTE;
                } else {
                    state = PLAIN;
                    builder.append(c);
                }
            } else if (state == INQUOTE) {
                if (c == '\\') {
                    if (i + 1 < cmdLen) {
                        i += 1;
                        builder.append(cmd.charAt(i));
                    }
                } else if (c == '"') {
                    state = PLAIN;
                } else {
                    builder.append(c);
                }
            }
        }
        if (builder.length() > 0) {
            result.add(builder.toString());
        }
        return result;
    }

    @Override
    public void updateSize(int columns, int rows) {
        if (VTTEST_MODE) {
            columns = 80;
            rows = 24;
        }
        // Inform the attached pty of our new size:
        Exec.setPtyWindowSize(mTermFd, rows, columns, 0, 0);
        super.updateSize(columns, rows);
    }

    /* XXX We should really get this ourselves from the resource bundle, but
       we cannot hold a context */
    public void setProcessExitMessage(String message) {
        mProcessExitMessage = message;
    }

    private void onProcessExit(int result) {
        if (mSettings.closeWindowOnProcessExit()) {
            finish();
        } else if (mProcessExitMessage != null) {
            try {
                byte[] msg = ("\r\n[" + mProcessExitMessage + "]").getBytes("UTF-8");
                appendToEmulator(msg, 0, msg.length);
                notifyUpdate();
            } catch (UnsupportedEncodingException e) {
                // Never happens
            }
        }
    }

    @Override
    public void finish() {
    	//Log.d("ShellTermSession", "finish");
        Exec.hangupProcessGroup(mProcId);
        Exec.close(mTermFd);
        super.finish();
    }

    /**
     * Gets the terminal session's title.  Unlike the superclass's getTitle(),
     * if the title is null or an empty string, the provided default title will
     * be returned instead.
     *
     * @param defaultTitle The default title to use if this session's title is
     *     unset or an empty string.
     */
    public String getTitle(String defaultTitle) {
        String title = super.getTitle();
        if (title != null && title.length() > 0) {
            return title;
        } else {
            return defaultTitle;
        }
    }

    public void setHandle(String handle) {
        if (mHandle != null) {
            throw new IllegalStateException("Cannot change handle once set");
        }
        mHandle = handle;
    }

    public String getHandle() {
        return mHandle;
    }
}
