package org.discord.utils;

import okhttp3.Credentials;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

public class ChallongeApiClient {
    private static final String BASE_URL = "https://api.challonge.com/v1/";
    private final ChallongeService challongeService;

    public ChallongeApiClient(String apiKey,String username) {
        HttpLoggingInterceptor loggingInterceptor = new HttpLoggingInterceptor(System.out::println);
        loggingInterceptor.setLevel(HttpLoggingInterceptor.Level.BODY);

        // Authentication Interceptor using OkHttp's Credentials.basic
        OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(chain -> {
                    Request original = chain.request();
                    String credential = Credentials.basic(username, apiKey); // API key as username, empty password
                    Request request = original.newBuilder()
                            .header("Authorization", credential)
                            .build();
                    return chain.proceed(request);
                })
                .addInterceptor(loggingInterceptor)
                .build();

        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl(BASE_URL)
                .addConverterFactory(GsonConverterFactory.create())
                .client(client)
                .build();

        challongeService = retrofit.create(ChallongeService.class);
    }

    public ChallongeService getService() {
        return challongeService;
    }
}
