package com.exteragram.messenger.plugins.models;

import android.view.View;

import com.chaquo.python.PyObject;

import org.telegram.ui.Components.UItem;

import app.exteraless.plugins.models.SettingItem;

/**
 * Произвольная строка настроек: либо готовый {@link UItem}, либо вью, либо фабрика.
 *
 * Базовый класс для {@code app.exteraless.plugins.models.CustomSetting} — тот же
 * приём, что у {@code com.exteragram.messenger.plugins.Plugin}: в стоковом
 * exteraGram класс лежит именно по этому пути, и плагины каталога обращаются
 * к нему отсюда ({@code from com.exteragram.messenger.plugins.models import
 * CustomSetting}, {@code @java_subclass(CustomSetting.Factory)}), поэтому
 * класс обязан существовать с этим именем, а его фабрика — быть тем же
 * классом, что у движка: подкласс фабрики отсюда ЯВЛЯЕТСЯ фабрикой
 * {@code app.exteraless.plugins.models.CustomSetting} (она наследуется
 * отсюда), и движок принимает его в {@code getFactory()} / {@code create()}.
 *
 * Вызовы у плагинов скомпилированы по типу
 * {@code com.exteragram.messenger.plugins.models.CustomSetting}, поэтому
 * объявлено всё, что публично у модели эталона и есть у нас.
 */
public abstract class CustomSetting extends SettingItem {

    public static abstract class Factory<V extends View> extends UItem.UItemFactory<V> {

        private boolean isShadowValue;
        private boolean isClickableValue = true;

        public UItem create(com.exteragram.messenger.plugins.Plugin plugin, CustomSetting setting, PyObject args) {
            return null;
        }

        public void onClick(com.exteragram.messenger.plugins.Plugin plugin, UItem item, View view) {
        }

        public void onLongClick(com.exteragram.messenger.plugins.Plugin plugin, UItem item, View view) {
        }

        @Override
        public boolean isShadow() {
            return isShadowValue;
        }

        @Override
        public boolean isClickable() {
            return isClickableValue;
        }

        public final boolean isShadowValue() {
            return isShadowValue;
        }

        public final void setShadowValue(boolean value) {
            this.isShadowValue = value;
        }

        public final boolean isClickableValue() {
            return isClickableValue;
        }

        public final void setClickableValue(boolean value) {
            this.isClickableValue = value;
        }
    }

    protected CustomSetting(String type, String icon, PyObject onLongClickCallback, String linkAlias) {
        super(type, icon, onLongClickCallback, linkAlias);
    }

    // NOTE: cleanup() is deliberately NOT re-declared abstract here:
    // SettingItem already implements it, and the app subclass calls
    // super.cleanup() — re-declaring it abstract would make that call
    // resolve to an abstract method (AbstractMethodError at runtime).

    public abstract PyObject getOnClickCallback();

    public abstract void setOnClickCallback(PyObject callback);

    public abstract PyObject getCreateSubFragmentCallback();

    public abstract void setCreateSubFragmentCallback(PyObject callback);

    public abstract PyObject getFactoryArgs();

    public abstract void setFactoryArgs(PyObject args);

    public abstract Factory<?> getFactory();

    public abstract void setFactory(Factory<?> factory);

    public abstract UItem getItem();

    public abstract void setItem(UItem item);
}
