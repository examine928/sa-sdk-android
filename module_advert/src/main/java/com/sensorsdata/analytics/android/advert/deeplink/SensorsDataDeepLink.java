/*
 * Created by chenru on 2022/4/25 下午5:05(format year/.
 * Copyright 2015－2022 Sensors Data Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.sensorsdata.analytics.android.advert.deeplink;

import android.content.Intent;
import android.net.Uri;
import android.text.TextUtils;

import com.sensorsdata.analytics.android.advert.utils.ChannelUtils;
import com.sensorsdata.analytics.android.sdk.SALog;
import com.sensorsdata.analytics.android.sdk.SensorsDataAPI;
import com.sensorsdata.analytics.android.sdk.ServerUrl;
import com.sensorsdata.analytics.android.sdk.network.HttpCallback;
import com.sensorsdata.analytics.android.sdk.network.HttpMethod;
import com.sensorsdata.analytics.android.sdk.network.RequestHelper;
import com.sensorsdata.analytics.android.sdk.util.JSONUtils;
import com.sensorsdata.analytics.android.sdk.util.NetworkUtils;
import com.sensorsdata.analytics.android.sdk.util.SensorsDataUtils;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

class SensorsDataDeepLink extends AbsDeepLink {
    private String serverUrl;
    private String project;
    private String pageParams;
    private String errorMsg;
    private boolean success;
    private String customADChannelUrl;
    private String adSlinkId;

    public SensorsDataDeepLink(Intent intent, String serverUrl, String customADChannelUrl) {
        super(intent);
        this.serverUrl = serverUrl;
        this.customADChannelUrl = customADChannelUrl;
        project = new ServerUrl(serverUrl).getProject();
    }

    @Override
    public void parseDeepLink(Intent intent) {
        if (intent == null || intent.getData() == null) {
            return;
        }
        Uri uri = intent.getData();
        String key = uri.getLastPathSegment();
        if (!TextUtils.isEmpty(key)) {
            final long requestDeepLinkStartTime = System.currentTimeMillis();
            final Map<String, String> params = new HashMap<>();
            params.put("key", key);
            params.put("system_type", "ANDROID");
            params.put("project", project);
            new RequestHelper.Builder(HttpMethod.GET, isSlink(uri, NetworkUtils.getHost(customADChannelUrl)) ? getSlinkRequestUrl() : getRequestUrl())
                    .params(params)
                    .callback(new HttpCallback.JsonCallback() {
                        @Override
                        public void onFailure(int code, String errorMessage) {
                            errorMsg = errorMessage;
                            success = false;
                        }

                        @Override
                        public void onResponse(JSONObject response) {
                            if (response != null) {
                                success = true;
                                JSONObject channel = response.optJSONObject("channel_params");
                                Map<String, String> params = JSONUtils.json2Map(channel);
                                ChannelUtils.parseParams(params);
                                pageParams = response.optString("page_params");
                                errorMsg = response.optString("errorMsg");
                                if (TextUtils.isEmpty(errorMsg)) { //兼容 slink 接口
                                    errorMsg = response.optString("error_msg");
                                }
                                adSlinkId = response.optString("ad_slink_id");
                                if (!TextUtils.isEmpty(errorMsg)) {
                                    success = false;
                                }
                            } else {
                                success = false;
                            }
                        }

                        @Override
                        public void onAfter() {
                            long duration = System.currentTimeMillis() - requestDeepLinkStartTime;
                            JSONObject properties = new JSONObject();
                            try {
                                if (!TextUtils.isEmpty(pageParams)) {
                                    properties.put("$deeplink_options", pageParams);
                                }
                                if (!TextUtils.isEmpty(errorMsg)) {
                                    properties.put("$deeplink_match_fail_reason", errorMsg);
                                }
                                if (!TextUtils.isEmpty(adSlinkId)) {
                                    properties.put("$ad_slink_id", adSlinkId);
                                }
                                properties.put("$deeplink_url", getDeepLinkUrl());
                                properties.put("$event_duration", String.format(Locale.CHINA, "%.3f", duration / 1000.0f));
                            } catch (JSONException e) {
                                SALog.printStackTrace(e);
                            }
                            SensorsDataUtils.mergeJSONObject(ChannelUtils.getUtmProperties(), properties);
                            if (mCallBack != null) {
                                mCallBack.onFinish(DeepLinkManager.DeepLinkType.SENSORSDATA, pageParams, success, duration);
                            }
                            SensorsDataAPI.sharedInstance().trackInternal("$AppDeeplinkMatchedResult", properties);
                        }
                    }).execute();
        }
    }

    @Override
    public void mergeDeepLinkProperty(JSONObject properties) {
        try {
            properties.put("$deeplink_url", getDeepLinkUrl());
        } catch (JSONException e) {
            SALog.printStackTrace(e);
        }
    }

    public String getRequestUrl() {
        if (!TextUtils.isEmpty(serverUrl)) {
            int pathPrefix = serverUrl.lastIndexOf("/");
            if (pathPrefix != -1) {
                return serverUrl.substring(0, pathPrefix) + "/sdk/deeplink/param";
            }
        }
        return "";
    }

    private boolean isSlink(Uri uri, String customADChannelUrl) {
        if (TextUtils.isEmpty(customADChannelUrl)) {
            return false;
        }
        List<String> paths = uri.getPathSegments();
        if (null != paths && !paths.isEmpty() && paths.get(0).equals("slink")) {
            String host = uri.getHost();
            return !TextUtils.isEmpty(host) && (NetworkUtils.compareMainDomain(customADChannelUrl, host) || host.equals("sensorsdata"));
        }
        return false;
    }

    private String getSlinkRequestUrl() {
        if (!TextUtils.isEmpty(customADChannelUrl)) {
            return NetworkUtils.getRequestUrl(customADChannelUrl, "slink/config/query");
        }
        return "";
    }

}
