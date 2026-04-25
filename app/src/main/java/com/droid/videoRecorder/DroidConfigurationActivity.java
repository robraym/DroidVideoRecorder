package com.droid.videoRecorder;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.SwitchPreference;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

/**
 * Created by Robson on 12/01/2016.
 */
public class DroidConfigurationActivity extends PreferenceActivity {
    private static final int REQUEST_RUNTIME_PERMISSIONS = 1001;

    private Context context;
    private ListPreference ltp_localGravacaoVideo;
    private SwitchPreference spf_aceitaComandoPorTexto;
    private boolean canFinish;
    private boolean serviceStarted;
    private boolean startupServiceRequested;
    private boolean overlayPermissionRequested;
    static int sdk_int = android.os.Build.VERSION.SDK_INT;

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
            addPreferencesFromResource(R.xml.preferences);

            ltp_localGravacaoVideo = (ListPreference) findPreference("ltp_localGravacaoVideo");
            ConfigurarLocaisGravacao();
            ltp_localGravacaoVideo.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
                @Override
                public boolean onPreferenceChange(Preference preference, Object newValue) {
                    preference.setSummary(DroidPrefsUtils.obtemDescricaoPreferencias(context, newValue.toString(), R.array.localArquivosGravados, R.array.valor_localArquivosGravados));
                    DroidVideoRecorder.LocalGravacaoVideo = Integer.parseInt(newValue.toString());
                    return true;
                }
            });


                spf_aceitaComandoPorTexto = (SwitchPreference) findPreference("spf_aceitaComandoPorTexto");

                spf_aceitaComandoPorTexto.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {

                    @Override
                    public boolean onPreferenceChange(Preference preference, Object newValue) {
                        try {
                            Boolean aceita = (Boolean) newValue;
                            Boolean status = DroidPrefsUtils.statusComandoPorTexto(context);
                            if (aceita != status) {
                                startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
                                canFinish = false;
                            }


                        } catch (Exception ex) {
                            Log.d("DVR", ex.getMessage());
                        }
                        return true;
                    }
                });

            if (sdk_int < 21) {

                spf_aceitaComandoPorTexto.setEnabled(false);
            }

        } else finish();

        StartServiceWhenReady(chamadaPeloServico);
    }

    private void ConfigurarLocaisGravacao() {
        boolean temCartaoSd = DroidPrefsUtils.temCartaoSd(context);
        if (temCartaoSd) {
            ltp_localGravacaoVideo.setEntries(R.array.localArquivosGravados);
            ltp_localGravacaoVideo.setEntryValues(R.array.valor_localArquivosGravados);
            ltp_localGravacaoVideo.setEnabled(true);
        } else {
            ltp_localGravacaoVideo.setEntries(new CharSequence[]{"Interno"});
            ltp_localGravacaoVideo.setEntryValues(new CharSequence[]{"0"});
            ltp_localGravacaoVideo.setValue("0");
            ltp_localGravacaoVideo.setEnabled(false);
        }

        ltp_localGravacaoVideo.setSummary(DroidPrefsUtils.obtemDescricaoPreferencias(context, String.valueOf(DroidPrefsUtils.obtemLocalGravacao(context)), R.array.localArquivosGravados, R.array.valor_localArquivosGravados));
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
                if (sdk_int >= 21) {
                    spf_aceitaComandoPorTexto.setChecked(DroidPrefsUtils.statusComandoPorTexto(context));
                }
            }

            if (startupServiceRequested && !serviceStarted) {
                StartServiceWhenReady(false);
            }
        }
        catch (Exception ex)
        {
            Log.d("DVR", ex.getMessage());
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
}
