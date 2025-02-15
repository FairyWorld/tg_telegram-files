"use client";

import { useToast } from "@/hooks/use-toast";
import {
  Toast,
  ToastClose,
  ToastDescription,
  ToastProvider,
  ToastTitle,
  ToastViewport,
} from "@/components/ui/toast";
import useIsMobile from "@/hooks/use-is-mobile";

export function Toaster() {
  const { toasts } = useToast();
  const isMobile = useIsMobile();

  return (
    <ToastProvider duration={isMobile ? 1000 : 5000}>
      {toasts.map(function ({ id, title, description, action, ...props }) {
        return (
          <Toast key={id} {...props}>
            <div className="grid gap-1" onClick={(e) => e.stopPropagation()}>
              {title && <ToastTitle>{title}</ToastTitle>}
              {description && (
                <ToastDescription>{description}</ToastDescription>
              )}
            </div>
            {action}
            <ToastClose onClick={(e) => e.stopPropagation()} />
          </Toast>
        );
      })}
      <ToastViewport />
    </ToastProvider>
  );
}
