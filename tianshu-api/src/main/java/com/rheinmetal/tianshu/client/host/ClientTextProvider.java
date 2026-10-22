package com.rheinmetal.tianshu.client.host;

import com.rheinmetal.tianshu.client.api.text.UiText;

public interface ClientTextProvider {
    String text(UiText text);

    String currentLanguage();
}
