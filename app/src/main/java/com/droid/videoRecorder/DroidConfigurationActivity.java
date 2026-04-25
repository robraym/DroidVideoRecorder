package com.droid.videoRecorder;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Created by Robson on 12/01/2016.
 */
public class DroidConfigurationActivity extends Activity {
    private static final int REQUEST_RUNTIME_PERMISSIONS = 1001;

    private Context context;
    private SharedPreferences preferences;
    private Switch commandSwitch;
    private TextView storageSummary;
    private boolean canFinish;
    private boolean serviceStarted;
    private boolean startupServiceRequested;
    private boolean overlayPermissionRequested;
    static int sdk_int = android.os.Build.VERSION.SDK_INT;
    private static final int COLOR_BACKGROUND = Color.rgb(0, 0, 0);
    private static final int COLOR_GROUP = Color.rgb(28, 29, 33);
    private static final int COLOR_PRIMARY_TEXT = Color.rgb(248, 248, 250);
    private static final int COLOR_SECONDARY_TEXT = Color.rgb(180, 182, 190);
    private static final int COLOR_DIVIDER = Color.rgb(52, 53, 58);

    private boolean ExibeTelaInicial() {
        return DroidPrefsUtils.exibeTelaInicial(context);
    }

    private boolean ChamadaPeloServico() {
        return DroidPrefsUtils.chamadaPeloServico(getIntent());
    }

    private boolean ChamadaConfigPorComandoTexto() {
        return DroidPrefsUtils.chamadaPorComandoTexto(getIntent());
    }

    private String ChamadaBroadCastPorComandoTexto() {
        return DroidPrefsUtils.chamadaBroadCastPorComandoTexto(getIntent());
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        context = getBaseContext();
        preferences = PreferenceManager.getDefaultSharedPreferences(context);
        boolean exibeTelaInicial = ExibeTelaInicial();
        boolean chamadaPeloServico = ChamadaPeloServico();
        boolean chamadaConfigPorComandoTexto = ChamadaConfigPorComandoTexto();

        if (exibeTelaInicial || chamadaPeloServico || chamadaConfigPorComandoTexto) {
            setTheme(R.style.DefaultTheme);
        } else {
            setTheme(R.style.TranslucentTheme);
        }
        super.onCreate(savedInstanceState);
        canFinish = true;

        if (exibeTelaInicial || chamadaPeloServico || chamadaConfigPorComandoTexto) {
            BuildSettingsScreen();
        } else finish();

        StartServiceWhenReady(chamadaPeloServico);
    }

    private void BuildSettingsScreen() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(COLOR_BACKGROUND);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(34), dp(20), dp(28));
        scrollView.addView(content, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText(getString(R.string.txt_titulo));
        title.setTextColor(COLOR_PRIMARY_TEXT);
        SetTextSize(title, 28);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.START);
        content.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView subtitle = new TextView(this);
        subtitle.setText(getString(R.string.app_name));
        subtitle.setTextColor(COLOR_SECONDARY_TEXT);
        SetTextSize(subtitle, 13);
        subtitle.setPadding(0, dp(4), 0, dp(22));
        content.addView(subtitle, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout generalGroup = CreateGroup();
        generalGroup.addView(CreateSwitchRow(
                getString(R.string.txt_titulo),
                getString(R.string.spf_exibe_iniciar),
                "spf_exibeAoIniciar",
                true,
                true,
                null));
        generalGroup.addView(CreateDivider());

        generalGroup.addView(CreateSwitchRow(
                getString(R.string.spf_titulo),
                getString(R.string.spf_exibe_tempo_gravacao),
                "spf_exibeTempoGravacao",
                true,
                true,
                null));
        generalGroup.addView(CreateDivider());

        generalGroup.addView(CreateStorageRow());
        content.addView(generalGroup);

        LinearLayout commandGroup = CreateGroup();
        LinearLayout.LayoutParams commandParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        commandParams.setMargins(0, dp(14), 0, 0);
        commandGroup.setLayoutParams(commandParams);

        commandGroup.addView(CreateSwitchRow(
                getString(R.string.comando),
                getString(R.string.aceitaComandoPorTexto),
                "spf_aceitaComandoPorTexto",
                false,
                sdk_int >= 21,
                new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                        try {
                            boolean status = DroidPrefsUtils.statusComandoPorTexto(context);
                            if (isChecked != status) {
                                canFinish = false;
                                startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
                            }
                        } catch (Exception ex) {
                            LogException("DVR", ex);
                        }
                    }
                }));
        commandGroup.addView(CreateDivider());

        commandGroup.addView(CreateSwitchRow(
                getString(R.string.fala),
                getString(R.string.leComando),
                "spf_leComando",
                false,
                true,
                null));
        content.addView(commandGroup);

        setContentView(scrollView);
        RefreshStorageSummary();
    }

    private View CreateSwitchRow(String title, String summary, final String key, boolean defaultValue, boolean enabled,
                                  CompoundButton.OnCheckedChangeListener extraListener) {
        LinearLayout row = CreateRow();
        row.setEnabled(enabled);

        LinearLayout texts = CreateTexts(title, summary);
        row.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        final Switch rowSwitch = new Switch(this);
        if ("spf_aceitaComandoPorTexto".equals(key)) {
            commandSwitch = rowSwitch;
        }
        StyleSwitch(rowSwitch);
        rowSwitch.setChecked(preferences.getBoolean(key, defaultValue));
        rowSwitch.setEnabled(enabled);
        rowSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                preferences.edit().putBoolean(key, isChecked).apply();
                if (buttonView.getTag() instanceof CompoundButton.OnCheckedChangeListener) {
                    ((CompoundButton.OnCheckedChangeListener) buttonView.getTag()).onCheckedChanged(buttonView, isChecked);
                }
            }
        });
        rowSwitch.setTag(extraListener);
        row.addView(rowSwitch);

        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (rowSwitch.isEnabled()) {
                    rowSwitch.setChecked(!rowSwitch.isChecked());
                }
            }
        });

        return row;
    }

    private View CreateStorageRow() {
        LinearLayout row = CreateRow();
        LinearLayout texts = CreateTexts(getString(R.string.txt_gravacao_videos), "");
        storageSummary = (TextView) texts.getChildAt(1);
        row.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        TextView arrow = new TextView(this);
        arrow.setText(">");
        arrow.setTextColor(Color.rgb(176, 179, 188));
        SetTextSize(arrow, 18);
        arrow.setGravity(Gravity.CENTER);
        row.addView(arrow, new LinearLayout.LayoutParams(dp(32), LinearLayout.LayoutParams.WRAP_CONTENT));

        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ShowStorageDialog();
            }
        });
        return row;
    }

    private LinearLayout CreateRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(14), dp(16), dp(14));
        return row;
    }

    private LinearLayout CreateGroup() {
        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.VERTICAL);
        group.setBackground(CreateRoundedBackground(COLOR_GROUP, 22));
        return group;
    }

    private View CreateDivider() {
        View divider = new View(this);
        divider.setBackgroundColor(COLOR_DIVIDER);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                Math.max(1, dp(1)));
        params.setMargins(dp(16), 0, dp(16), 0);
        divider.setLayoutParams(params);
        return divider;
    }

    private GradientDrawable CreateRoundedBackground(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private LinearLayout CreateTexts(String title, String summary) {
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(0, 0, dp(14), 0);

        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextColor(COLOR_PRIMARY_TEXT);
        SetTextSize(titleView, 16);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setSingleLine(false);
        texts.addView(titleView);

        TextView summaryView = new TextView(this);
        summaryView.setText(summary);
        summaryView.setTextColor(COLOR_SECONDARY_TEXT);
        SetTextSize(summaryView, 13);
        summaryView.setSingleLine(false);
        summaryView.setPadding(0, dp(4), 0, 0);
        texts.addView(summaryView);

        return texts;
    }

    private void StyleSwitch(Switch rowSwitch) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return;
        }

        int[][] states = new int[][]{
                new int[]{android.R.attr.state_checked},
                new int[]{-android.R.attr.state_enabled},
                new int[]{}
        };

        rowSwitch.setThumbTintList(new ColorStateList(states, new int[]{
                Color.rgb(66, 133, 244),
                Color.rgb(120, 123, 132),
                Color.rgb(232, 234, 237)
        }));
        rowSwitch.setTrackTintList(new ColorStateList(states, new int[]{
                Color.rgb(25, 78, 163),
                Color.rgb(50, 52, 58),
                Color.rgb(88, 91, 99)
        }));
    }

    private void ShowStorageDialog() {
        final boolean temCartaoSd = DroidPrefsUtils.temCartaoSd(context);
        final String[] entries = temCartaoSd
                ? getResources().getStringArray(R.array.localArquivosGravados)
                : new String[]{"Interno"};
        final String[] values = temCartaoSd
                ? getResources().getStringArray(R.array.valor_localArquivosGravados)
                : new String[]{"0"};

        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.txt_gravacao_videos))
                .setItems(entries, (dialog, which) -> {
                    preferences.edit().putString("ltp_localGravacaoVideo", values[which]).apply();
                    DroidVideoRecorder.LocalGravacaoVideo = Integer.parseInt(values[which]);
                    RefreshStorageSummary();
                })
                .show();
    }

    private void RefreshStorageSummary() {
        int storage = DroidPrefsUtils.obtemLocalGravacao(context);
        if (storageSummary != null) {
            storageSummary.setText(DroidPrefsUtils.obtemDescricaoPreferencias(
                    context,
                    String.valueOf(storage),
                    R.array.localArquivosGravados,
                    R.array.valor_localArquivosGravados));
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        StartServiceWhenReady(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        try {
            if (!ExibeTelaInicial() && !ChamadaPeloServico() && !ChamadaConfigPorComandoTexto()) {
                finish();
            } else {
                if (sdk_int >= 21 && commandSwitch != null) {
                    commandSwitch.setChecked(DroidPrefsUtils.statusComandoPorTexto(context));
                }
                RefreshStorageSummary();
            }

            if (startupServiceRequested && !serviceStarted) {
                StartServiceWhenReady(false);
            }
        }
        catch (Exception ex)
        {
            LogException("DVR", ex);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (canFinish) finish();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_RUNTIME_PERMISSIONS) {
            canFinish = true;
            if (HasRuntimePermissions()) {
                StartServiceWhenReady(false);
            } else {
                Toast.makeText(this, "Permita camera, microfone e armazenamento para iniciar o gravador.", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void StartServiceWhenReady(boolean chamadaPeloServico) {
        if (chamadaPeloServico || (serviceStarted && ChamadaBroadCastPorComandoTexto().isEmpty())) {
            return;
        }

        startupServiceRequested = true;

        if (!HasRuntimePermissions()) {
            RequestRuntimePermissions();
            return;
        }

        if (!HasOverlayPermission()) {
            RequestOverlayPermission();
            return;
        }

        Intent intentService = new Intent(context, DroidHeadService.class);
        intentService.putExtra(DroidConstants.CHAMADAPORCOMANDOTEXTO, ChamadaBroadCastPorComandoTexto());
        startService(intentService);
        serviceStarted = true;
        overlayPermissionRequested = false;
    }

    private boolean HasRuntimePermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }

        boolean hasMediaPermissions = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return false;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return hasMediaPermissions;
        }

        return hasMediaPermissions
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void RequestRuntimePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            canFinish = false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requestPermissions(new String[]{
                        Manifest.permission.CAMERA,
                        Manifest.permission.RECORD_AUDIO,
                        Manifest.permission.POST_NOTIFICATIONS
                }, REQUEST_RUNTIME_PERMISSIONS);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                requestPermissions(new String[]{
                        Manifest.permission.CAMERA,
                        Manifest.permission.RECORD_AUDIO
                }, REQUEST_RUNTIME_PERMISSIONS);
            } else {
                requestPermissions(new String[]{
                        Manifest.permission.CAMERA,
                        Manifest.permission.RECORD_AUDIO,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                }, REQUEST_RUNTIME_PERMISSIONS);
            }
        }
    }

    private boolean HasOverlayPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this);
    }

    private void RequestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (overlayPermissionRequested) {
                canFinish = true;
                Toast.makeText(this, "O gravador precisa da permissao de aparecer sobre outros apps.", Toast.LENGTH_LONG).show();
                return;
            }

            overlayPermissionRequested = true;
            canFinish = false;
            Toast.makeText(this, "Ative a permissao de aparecer sobre outros apps para iniciar o gravador.", Toast.LENGTH_LONG).show();
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void SetTextSize(TextView textView, int dpSize) {
        textView.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(dpSize));
    }

    private void LogException(String tag, Exception ex) {
        String message = ex.getMessage();
        Log.d(tag, message != null ? message : ex.toString());
    }
}
