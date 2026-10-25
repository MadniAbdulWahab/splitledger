import { useEffect, useState } from 'react';

// "Add to home screen": Chrome and Edge offer an install prompt the page can trigger from a
// button; Safari on iPhone has no such event, so it gets a short how-to instead.

interface InstallPromptEvent extends Event {
  prompt: () => Promise<void>;
  userChoice: Promise<{ outcome: 'accepted' | 'dismissed' }>;
}

export type InstallState =
  | { kind: 'installed' }
  | { kind: 'prompt'; install: () => Promise<void> }
  | { kind: 'ios' }
  | { kind: 'unavailable' };

const isStandalone = () => window.matchMedia('(display-mode: standalone)').matches
  || (navigator as { standalone?: boolean }).standalone === true;
const isIos = () => /iphone|ipad|ipod/i.test(navigator.userAgent) && !/crios|fxios/i.test(navigator.userAgent);

export function useInstall(): InstallState {
  const [event, setEvent] = useState<InstallPromptEvent | null>(null);
  const [installed, setInstalled] = useState(isStandalone);

  useEffect(() => {
    const onPrompt = (e: Event) => {
      e.preventDefault(); // keep it for the button instead of the browser's own banner
      setEvent(e as InstallPromptEvent);
    };
    const onInstalled = () => setInstalled(true);
    window.addEventListener('beforeinstallprompt', onPrompt);
    window.addEventListener('appinstalled', onInstalled);
    return () => {
      window.removeEventListener('beforeinstallprompt', onPrompt);
      window.removeEventListener('appinstalled', onInstalled);
    };
  }, []);

  if (installed) return { kind: 'installed' };
  if (event) {
    return {
      kind: 'prompt',
      install: async () => {
        await event.prompt();
        if ((await event.userChoice).outcome === 'accepted') setInstalled(true);
        setEvent(null);
      },
    };
  }
  return isIos() ? { kind: 'ios' } : { kind: 'unavailable' };
}

/** The service worker caches only the app shell; it is registered on secure pages only (as browsers require). */
export function registerServiceWorker() {
  if ('serviceWorker' in navigator && window.isSecureContext && import.meta.env.PROD) {
    window.addEventListener('load', () => { void navigator.serviceWorker.register('/sw.js'); });
  }
}
