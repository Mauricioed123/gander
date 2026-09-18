package com.arjun.gander

import android.content.Context
import androidx.annotation.StringRes
import androidx.annotation.StyleRes
import androidx.core.content.edit

/**
 * Las cuatro apariencias que puede vestir la app.
 *
 * Cada una es un estilo en `themes.xml` que cambia el acento y, en el caso de Grafito,
 * tambien el fondo. El nombre guardado es el de la constante, no un numero, para que
 * agregar una apariencia en el medio de la lista no cambie la que alguien ya eligio.
 *
 * Se aplica con `setTheme` en `onCreate`, antes de `super.onCreate`, en cada actividad:
 * la eleccion es del proceso, no de una pantalla, y una actividad que no lo llame
 * aparece con la apariencia anterior a la elegida.
 */
enum class Apariencia(@StyleRes val estilo: Int, @StringRes val nombre: Int) {
    ROSA(R.style.Theme_TriniOffice_Rosa, R.string.apariencia_rosa),
    GRAFITO(R.style.Theme_TriniOffice_Grafito, R.string.apariencia_grafito),
    AZUL(R.style.Theme_TriniOffice_Azul, R.string.apariencia_azul),
    GRIS(R.style.Theme_TriniOffice_Gris, R.string.apariencia_gris);

    companion object {

        private const val AJUSTES = "apariencia"
        private const val CLAVE = "elegida"

        fun actual(context: Context): Apariencia {
            val guardada = context.getSharedPreferences(AJUSTES, Context.MODE_PRIVATE)
                .getString(CLAVE, null)
            return entries.firstOrNull { it.name == guardada } ?: ROSA
        }

        fun fijar(context: Context, elegida: Apariencia) {
            context.getSharedPreferences(AJUSTES, Context.MODE_PRIVATE)
                .edit { putString(CLAVE, elegida.name) }
        }
    }
}
