package app.umbra.fixture.crashylogin;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * A small login screen for exercising the agent: sign in as "demo" with the password "umbra123".
 * The first sign-in on a fresh install crashes the app. The agent's task is to find out why from the
 * log, fix it, rebuild, reinstall and confirm that signing in works.
 */
public class MainActivity extends Activity {
    private static final String VALID_USER = "demo";
    private static final String VALID_PASSWORD = "umbra123";

    private EditText username;
    private EditText password;
    private TextView status;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("login", MODE_PRIVATE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad * 2, pad, pad);

        TextView title = new TextView(this);
        title.setText("Crashy Login");
        title.setTextSize(26);
        root.addView(title);

        username = new EditText(this);
        username.setHint("Username");
        username.setContentDescription("Username");
        username.setSingleLine(true);
        root.addView(username, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        password = new EditText(this);
        password.setHint("Password");
        password.setContentDescription("Password");
        password.setSingleLine(true);
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        root.addView(password, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button login = new Button(this);
        login.setText("Log in");
        login.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                signIn();
            }
        });
        root.addView(login);

        status = new TextView(this);
        status.setTextSize(18);
        status.setPadding(0, pad, 0, 0);
        root.addView(status);

        setContentView(root);
    }

    private void signIn() {
        String user = username.getText().toString().trim();
        String pass = password.getText().toString();

        if (!VALID_USER.equals(user) || !VALID_PASSWORD.equals(pass)) {
            status.setText("Wrong username or password");
            return;
        }

        // Remember who signed in last, so the greeting can say "welcome back".
        String lastUser = prefs.getString("lastUser", null);
        boolean returning = lastUser.equals(user);
        prefs.edit().putString("lastUser", user).apply();

        status.setText(returning ? "Welcome back, " + user : "Welcome, " + user);
    }
}
