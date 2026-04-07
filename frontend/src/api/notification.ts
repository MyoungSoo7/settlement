import api from './axios';
import { NotificationResponse } from '@/types';

export const notificationApi = {
  /**
   * 사용자별 알림 목록 조회
   * GET /api/notifications/user/{userId}
   */
  getUserNotifications: async (userId: number): Promise<NotificationResponse[]> => {
    const response = await api.get<NotificationResponse[]>(`/api/notifications/user/${userId}`);
    return response.data;
  },

  /**
   * 읽지 않은 알림 목록 조회
   * GET /api/notifications/user/{userId}/unread
   */
  getUnreadNotifications: async (userId: number): Promise<NotificationResponse[]> => {
    const response = await api.get<NotificationResponse[]>(`/api/notifications/user/${userId}/unread`);
    return response.data;
  },

  /**
   * 읽지 않은 알림 수 조회
   * GET /api/notifications/user/{userId}/unread/count
   */
  getUnreadCount: async (userId: number): Promise<number> => {
    const response = await api.get<number>(`/api/notifications/user/${userId}/unread/count`);
    return response.data;
  },

  /**
   * 알림 읽음 처리
   * PATCH /api/notifications/{id}/read
   */
  markAsRead: async (id: number): Promise<NotificationResponse> => {
    const response = await api.patch<NotificationResponse>(`/api/notifications/${id}/read`);
    return response.data;
  },

  /**
   * 모든 알림 읽음 처리
   * PATCH /api/notifications/user/{userId}/read-all
   */
  markAllAsRead: async (userId: number): Promise<void> => {
    await api.patch(`/api/notifications/user/${userId}/read-all`);
  },
};
