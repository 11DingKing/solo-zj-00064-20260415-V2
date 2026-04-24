import axios, { AxiosError, AxiosInstance, InternalAxiosRequestConfig } from "axios";

import { useSession } from "../providers/session";

const ENV = {
  BASE_URL: import.meta.env.VITE_API_BASE_URL,
};

export const api = axios.create({
  baseURL: ENV.BASE_URL,
});

let refreshTokenPromise: Promise<string> | null = null;

const refreshToken = async (): Promise<string> => {
  const { session, set: setSession } = useSession.getState();

  if (!session) {
    throw new Error("No session available");
  }

  try {
    const response = await axios.post(
      `${ENV.BASE_URL}/api/authentication/refresh`,
      {
        refreshToken: session.refreshToken,
      },
    );

    const newSession = response.data;
    setSession(newSession);

    return newSession.accessToken;
  } catch (error) {
    const { set: setSession } = useSession.getState();
    setSession(null);
    throw error;
  }
};

const getRefreshTokenPromise = (): Promise<string> => {
  if (!refreshTokenPromise) {
    refreshTokenPromise = refreshToken().finally(() => {
      refreshTokenPromise = null;
    });
  }
  return refreshTokenPromise;
};

const handleTokenExpired = async (
  error: AxiosError,
  instance: AxiosInstance,
): Promise<unknown> => {
  const originalRequest = error.config as InternalAxiosRequestConfig & {
    _retry?: boolean;
  };

  if (originalRequest._retry) {
    return Promise.reject(error);
  }

  originalRequest._retry = true;

  try {
    const newAccessToken = await getRefreshTokenPromise();

    if (originalRequest.headers) {
      originalRequest.headers.Authorization = `Bearer ${newAccessToken}`;
    }

    return instance(originalRequest);
  } catch (refreshError) {
    return Promise.reject(refreshError);
  }
};

api.interceptors.request.use(async (config) => {
  const { session, set: setSession } = useSession.getState();

  if (session) {
    if (session.isExpired()) {
      try {
        const newAccessToken = await getRefreshTokenPromise();
        config.headers = {
          ...config.headers,
          Authorization: `Bearer ${newAccessToken}`,
        };
        return config;
      } catch (error) {
        setSession(null);
        throw error;
      }
    }

    config.headers = {
      ...config.headers,
      Authorization: `Bearer ${session.accessToken}`,
    };
  }

  return config;
});

api.interceptors.response.use(
  (response) => response,
  async (error: AxiosError) => {
    if (error.response?.status === 401) {
      return handleTokenExpired(error, api);
    }
    return Promise.reject(error);
  },
);
