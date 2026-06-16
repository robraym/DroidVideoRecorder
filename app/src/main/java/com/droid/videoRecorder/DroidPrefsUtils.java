package com.droid.videoRecorder;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Environment;
import android.preference.PreferenceManager;
import android.util.Log;

import java.io.File;

/**
 * Created by Robson on 12/01/2016.
 */

public class DroidPrefsUtils {
    private static final String PREF_ULTIMA_CAMERA = "spf_ultimaCamera";
    private static final String PREF_TAMANHO_BOLINHA = "spf_tamanhoBolinha";
    private static final String CAMERA_FRONTAL = "front";
    private static final String CAMERA_TRASEIRA = "back";

    public static boolean chamadaPeloServico(final Intent intent) {
        boolean chamadaPeloServico = false;
        try {

            chamadaPeloServico = intent.getBooleanExtra(DroidConstants.CHAMADAPELOSERVICO, false);
        } catch (Exception ex) {

        }
        return chamadaPeloServico;
    }

    public static boolean exibeTelaInicial(final Context context) {
        boolean spf = false;
        try {
            SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
            spf = sp.getBoolean("spf_exibeAoIniciar", true);
        } catch (Exception ex) {
            Log.d("DroidVideo", ex.getMessage());
        }
        return spf;

    }

    public static boolean leComando(final Context context) {
        boolean spf = false;
        try {
            SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
            spf = sp.getBoolean("spf_leComando", false);
        } catch (Exception ex) {
            Log.d("DroidVideo", ex.getMessage());
        }
        return spf;

    }


    public static boolean exibeTempoGravacao(final Context context) {
        boolean spf = false;
        try {
            SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
            spf = sp.getBoolean("spf_exibeTempoGravacao", true);
        } catch (Exception ex) {
            Log.d("DroidVideo", ex.getMessage());
        }
        return spf;

    }

    public static boolean salvaSelfiesComoVisualizadas(final Context context) {
        boolean spf = false;
        try {
            SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
            spf = sp.getBoolean("spf_salvarSelfiesComoVisualizadas", false);
        } catch (Exception ex) {
            Log.d("DroidVideo", ex.getMessage());
        }
        return spf;
    }

    public static boolean revisarVideoAposGravar(final Context context) {
        boolean spf = false;
        try {
            SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
            spf = sp.getBoolean("spf_revisarVideoAposGravar", false);
        } catch (Exception ex) {
            Log.d("DroidVideo", ex.getMessage());
        }
        return spf;
    }

    public static int obtemTamanhoBolinha(final Context context, int tamanhoPadrao, int tamanhoMinimo, int tamanhoMaximo) {
        int tamanho = tamanhoPadrao;
        try {
            SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
            tamanho = sp.getInt(PREF_TAMANHO_BOLINHA, tamanhoPadrao);
        } catch (Exception ex) {
            Log.d("DroidVideo", ex.getMessage());
        }
        return Math.max(tamanhoMinimo, Math.min(tamanhoMaximo, tamanho));
    }

    public static void salvaTamanhoBolinha(final Context context, int tamanho) {
        try {
            SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
            sp.edit().putInt(PREF_TAMANHO_BOLINHA, tamanho).apply();
        } catch (Exception ex) {
            Log.d("DroidVideo", ex.getMessage());
        }
    }

    public static int obtemLocalGravacao(final Context context) {
        int local = 0; // Interno
        try {
            SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
            local = Integer.parseInt(sp.getString("ltp_localGravacaoVideo", "0"));
            if (local == 1 && !temCartaoSd(context)) {
                local = 0;
                sp.edit().putString("ltp_localGravacaoVideo", "0").apply();
            }
        } catch (Exception ex) {
            Log.d("DroidVideo", ex.getMessage());
        }
        return local;

    }

    public static boolean temCartaoSd(final Context context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                File[] directories = context.getExternalFilesDirs(Environment.DIRECTORY_MOVIES);
                if (directories != null) {
                    for (int i = 1; i < directories.length; i++) {
                        File directory = directories[i];
                        if (directory != null && Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState(directory))) {
                            return true;
                        }
                    }
                }
            }

            String secondaryStorage = System.getenv("SECONDARY_STORAGE");
            String externalSdCardStorage = System.getenv("EXTERNAL_SDCARD_STORAGE");
            return (secondaryStorage != null && secondaryStorage.length() > 0)
                    || (externalSdCardStorage != null && externalSdCardStorage.length() > 0);
        } catch (Exception ex) {
            Log.d("DroidVideo", ex.getMessage());
        }
        return false;
    }

    public static DroidConstants.EnumTypeViewCam obtemUltimaCamera(final Context context) {
        DroidConstants.EnumTypeViewCam typeViewCam = DroidConstants.EnumTypeViewCam.FacingBack;
        try {
            SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
            String camera = sp.getString(PREF_ULTIMA_CAMERA, CAMERA_TRASEIRA);
            if (CAMERA_FRONTAL.equals(camera)) {
                typeViewCam = DroidConstants.EnumTypeViewCam.FacingFront;
            }
        } catch (Exception ex) {
            Log.d("DroidVideo", ex.getMessage());
        }
        return typeViewCam;
    }

    public static void salvaUltimaCamera(final Context context, DroidConstants.EnumTypeViewCam typeViewCam) {
        try {
            SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
            String camera = typeViewCam == DroidConstants.EnumTypeViewCam.FacingFront ? CAMERA_FRONTAL : CAMERA_TRASEIRA;
            sp.edit().putString(PREF_ULTIMA_CAMERA, camera).apply();
        } catch (Exception ex) {
            Log.d("DroidVideo", ex.getMessage());
        }
    }

    public static String obtemDescricaoPreferencias(final Context context, String valor_selecionado, int nome_lista, int lista_valor) {
        String nome_selecionado = "";

        String[] array_lista = context.getResources().getStringArray(nome_lista);
        String[] array_lista_valores = context.getResources().getStringArray(lista_valor);

        for (int i = 0; i < array_lista_valores.length; i++) {
            if (array_lista_valores[i].equals(valor_selecionado)) {
                nome_selecionado = array_lista[i].toString();
                break;
            }
        }
        return nome_selecionado;
    }


}
