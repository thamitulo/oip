import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

/** Types for the "My focus" API (/api/v1/focus). Kept local to this feature. */
export interface GuidanceLink { label: string; url: string; note: string | null; }

export interface FocusTemplate {
  code: string;
  name: string;
  description: string;
  keywords: string[];
  eventTypes: string[];
  guidance: GuidanceLink[];
}

export interface FocusArea {
  id: string;
  name: string;
  description: string | null;
  templateCode: string | null;
  keywords: string[];
  excludeKeywords: string[];
  eventTypes: string[];
  provinces: string[];
  sectors: string[];
  includeClosed: boolean;
  searchDocuments: boolean;
  notifyMe: boolean;
  createdAt: string;
  updatedAt: string;
  guidance: GuidanceLink[];
}

export interface FocusRequest {
  name: string;
  description?: string | null;
  templateCode?: string | null;
  keywords: string[];
  excludeKeywords: string[];
  eventTypes: string[];
  provinces: string[];
  sectors: string[];
  includeClosed: boolean;
  searchDocuments: boolean;
  notifyMe: boolean;
}

export interface FocusPage<T> { content: T[]; page: number; size: number; totalElements: number; totalPages: number; }

@Injectable({ providedIn: 'root' })
export class FocusService {
  private readonly http = inject(HttpClient);

  templates(): Observable<FocusTemplate[]> {
    return this.http.get<FocusTemplate[]>('/api/v1/focus/templates');
  }
  list(): Observable<FocusArea[]> {
    return this.http.get<FocusArea[]>('/api/v1/focus');
  }
  create(body: FocusRequest): Observable<FocusArea> {
    return this.http.post<FocusArea>('/api/v1/focus', body);
  }
  update(id: string, body: FocusRequest): Observable<FocusArea> {
    return this.http.put<FocusArea>(`/api/v1/focus/${id}`, body);
  }
  delete(id: string): Observable<void> {
    return this.http.delete<void>(`/api/v1/focus/${id}`);
  }
  // The opportunity summaries use the catalogue's shape; typed loosely here to keep this feature self-contained.
  opportunities<T>(id: string, page = 0, size = 20): Observable<FocusPage<T>> {
    return this.http.get<FocusPage<T>>(`/api/v1/focus/${id}/opportunities`, {
      params: new HttpParams().set('page', page).set('size', size),
    });
  }
}
