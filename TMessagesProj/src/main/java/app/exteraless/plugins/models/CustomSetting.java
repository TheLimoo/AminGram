package app.exteraless.plugins.models;

import android.view.View;

import com.chaquo.python.PyObject;

import org.telegram.ui.Components.UItem;

/**
 * Произвольная строка настроек: либо готовый {@link UItem}, либо вью, либо фабрика.
 *
 * Наследник {@code com.exteragram.messenger.plugins.models.CustomSetting} — та же
 * схема, что у {@code Plugin}: фасад по пути стокового exteraGram, реализация
 * здесь. Фабрика ({@code Factory}) живёт в фасаде и наследуется отсюда, поэтому
 * подкласс фабрики, созданный плагином из Python через
 * {@code @java_subclass(CustomSetting.Factory)}, является и нашей фабрикой.
 */
public class CustomSetting extends com.exteragram.messenger.plugins.models.CustomSetting {

    private PyObject onClickCallback;
    private PyObject createSubFragmentCallback;
    private PyObject factoryArgs;
    private Factory<?> factory;
    private UItem item;

    private CustomSetting(PyObject onClickCallback, PyObject createSubFragmentCallback,
                          PyObject onLongClickCallback, String linkAlias) {
        super("custom", null, onLongClickCallback, linkAlias);
        this.onClickCallback = onClickCallback;
        this.createSubFragmentCallback = createSubFragmentCallback;
    }

    public CustomSetting(UItem item, PyObject onClickCallback, PyObject createSubFragmentCallback,
                         PyObject onLongClickCallback, String linkAlias) {
        this(onClickCallback, createSubFragmentCallback, onLongClickCallback, linkAlias);
        this.item = item;
        item.settingItem = this;
    }

    public CustomSetting(View view, PyObject onClickCallback, PyObject createSubFragmentCallback,
                         PyObject onLongClickCallback, String linkAlias) {
        this(UItem.asCustom(view), onClickCallback, createSubFragmentCallback, onLongClickCallback, linkAlias);
    }

    public CustomSetting(Factory<?> factory, PyObject onClickCallback, PyObject createSubFragmentCallback,
                         PyObject onLongClickCallback, String linkAlias) {
        this(onClickCallback, createSubFragmentCallback, onLongClickCallback, linkAlias);
        this.factory = factory;
    }

    public CustomSetting(Factory<?> factory, PyObject factoryArgs, PyObject onClickCallback,
                         PyObject createSubFragmentCallback, PyObject onLongClickCallback, String linkAlias) {
        this(factory, onClickCallback, createSubFragmentCallback, onLongClickCallback, linkAlias);
        this.factoryArgs = factoryArgs;
    }

    @Override
    public void cleanup() {
        super.cleanup();
        closeCallback(onClickCallback);
        closeCallback(createSubFragmentCallback);
        closeCallback(factoryArgs);
        onClickCallback = null;
        createSubFragmentCallback = null;
        factoryArgs = null;
    }

    public PyObject getOnClickCallback() {
        return onClickCallback;
    }

    public void setOnClickCallback(PyObject callback) {
        this.onClickCallback = callback;
    }

    public PyObject getCreateSubFragmentCallback() {
        return createSubFragmentCallback;
    }

    public void setCreateSubFragmentCallback(PyObject callback) {
        this.createSubFragmentCallback = callback;
    }

    public PyObject getFactoryArgs() {
        return factoryArgs;
    }

    public void setFactoryArgs(PyObject args) {
        this.factoryArgs = args;
    }

    public Factory<?> getFactory() {
        return factory;
    }

    public void setFactory(Factory<?> factory) {
        this.factory = factory;
    }

    public UItem getItem() {
        return item;
    }

    public void setItem(UItem item) {
        this.item = item;
    }
}
