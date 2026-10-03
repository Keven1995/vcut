"use client";

import { useEffect, useRef, useState } from "react";

type CacheEntry = {
  data: unknown;
  updatedAt: number;
};

export type ServerQuery<T> = {
  readonly data: T | null;
  readonly error: Error | null;
  readonly isLoading: boolean;
  readonly isFetching: boolean;
  readonly refetch: () => Promise<T | null>;
};

const cache = new Map<string, CacheEntry>();
const subscribers = new Map<string, Set<() => void>>();

export function invalidateServerQuery(key: string): void {
  cache.delete(key);
  subscribers.get(key)?.forEach((notify) => notify());
}

export function useServerQuery<T>(
  key: string,
  queryFn: () => Promise<T>,
  enabled = true
): ServerQuery<T> {
  const queryFnRef = useRef(queryFn);
  const cached = cache.get(key);
  const [data, setData] = useState<T | null>(() => (cached ? (cached.data as T) : null));
  const [error, setError] = useState<Error | null>(null);
  const [isFetching, setIsFetching] = useState(enabled);
  const [revision, setRevision] = useState(0);

  useEffect(() => {
    queryFnRef.current = queryFn;
  }, [queryFn]);

  useEffect(() => {
    const notify = (): void => setRevision((current) => current + 1);
    const listeners = subscribers.get(key) ?? new Set<() => void>();
    listeners.add(notify);
    subscribers.set(key, listeners);
    return () => {
      listeners.delete(notify);
      if (listeners.size === 0) {
        subscribers.delete(key);
      }
    };
  }, [key]);

  useEffect(() => {
    let active = true;
    if (!enabled) {
      return () => {
        active = false;
      };
    }
    void queryFnRef.current()
      .then((next) => {
        if (!active) {
          return;
        }
        cache.set(key, { data: next, updatedAt: Date.now() });
        setData(next);
        setError(null);
      })
      .catch((caught: unknown) => {
        if (active) {
          setError(caught instanceof Error ? caught : new Error("Não foi possível consultar o servidor."));
        }
      })
      .finally(() => {
        if (active) {
          setIsFetching(false);
        }
      });
    return () => {
      active = false;
    };
  }, [enabled, key, revision]);

  async function refetch(): Promise<T | null> {
    if (!enabled) {
      return null;
    }
    setIsFetching(true);
    try {
      const next = await queryFnRef.current();
      cache.set(key, { data: next, updatedAt: Date.now() });
      setData(next);
      setError(null);
      return next;
    } catch (caught: unknown) {
      const nextError = caught instanceof Error ? caught : new Error("Não foi possível consultar o servidor.");
      setError(nextError);
      return null;
    } finally {
      setIsFetching(false);
    }
  }

  return {
    data,
    error,
    isLoading: enabled && data === null && isFetching,
    isFetching,
    refetch
  };
}
