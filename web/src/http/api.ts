import axios, { AxiosError, AxiosInstance, InternalAxiosRequestConfig } from "axios";

import { useSession } from "../providers/session";

const ENV = {
  BASE_URL: import.meta.env.VITE_API_BASE_URL,
};

export const api = axios.create({
  baseURL: ENV.BASE_URL,
});

api.interceptors.request.use(async (configs) => {
  const { session, set: setSession } = useSession.getState();

  if (session) {
    if (session.isExpired()) {
      try {
        const { data: sessionResponse } = await axios.post(
          `${ENV.BASE_URL}/v1/authentication/refresh`,
          {
            refreshToken: session.refreshToken,
          },
        );
        setSession(sessionResponse);
      } catch (error) {
        setSession(null);
      }
    }

    configs.headers = {
      ...configs.headers,
      Authorization: `Bearer ${session.accessToken}`,
    };
  }

  return configs;
});
}

  const response = await axios.post(`${ENV.BASE_URL}/api/authentication/refresh`, {
    refreshToken: session.refreshToken
  });

  const newSession = response.data;
  setSession(newSession);
  
  return newSession.accessToken;
};

const handleTokenExpired = async (
  error: AxiosError,
  instance: AxiosInstance
): Promise<unknown> => {
  const originalRequest = error.config as InternalAxiosRequestConfig & { _retry?: boolean };
  
  if (originalRequest._retry) {
    return Promise.reject(error);
  }

  if (isRefreshing) {
    return new Promise((resolve, reject) => {
      failedQueue.push({ resolve, reject });
    })
      .then((token) => {
        if (token && originalRequest.headers) {
          originalRequest.headers.Authorization = `Bearer ${token}`;
        }
        return instance(originalRequest);
      })
      .catch((err) => Promise.reject(err));
  }

  originalRequest._retry = true;
  isRefreshing = true;

  try {
    const newAccessToken = await refreshToken();
    processQueue(null, newAccessToken);
    
    if (originalRequest.headers) {
      originalRequest.headers.Authorization = `Bearer ${newAccessToken}`;
    }
    
    return instance(originalRequest);
  } catch (refreshError) {
    processQueue(refreshError, null);
    const { set: setSession } = useSession.getState();
    setSession(null);
    return Promise.reject(refreshError);
  } finally {
    isRefreshing = false;
  }
};

api.interceptors.request.use(async (config) => {
  const { session, set: setSession } = useSession.getState();

  if (session) {
    if (session.isExpired()) {
      try {        
        const { data: sessionResponse } = await axios.post(`${ENV.BASE_URL}/api/authentication/refresh`, {
          refreshToken: session.refreshToken
        });
        setSession(sessionResponse);
        
        const newSession = useSession.getState().session;
        if (newSession) {
          config.headers = {
            ...config.headers,
            'Authorization': `Bearer ${newSession.accessToken}`,
          };
        }
        return config;
      } catch (error) {
        setSession(null);
        throw error;
      }
    }

    config.headers = {
      ...config.headers,
      'Authorization': `Bearer ${session.accessToken}`,
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
  }
);
