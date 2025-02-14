import type { TelegramFile } from "@/lib/types";
import React, { useEffect, useState } from "react";
import { Drawer, DrawerContent, DrawerTitle } from "@/components/ui/drawer";
import { cn } from "@/lib/utils";
import { VisuallyHidden } from "@radix-ui/react-visually-hidden";
import { LoaderPinwheel, Minimize2 } from "lucide-react";
import { AnimatePresence, motion } from "framer-motion";
import { Button } from "@/components/ui/button";
import VideoPreview from "@/components/video-preview";
import PhotoPreview from "@/components/photo-preview";
import FileInfo from "@/components/mobile/file-info";
import { type useFiles } from "@/hooks/use-files";
import useFileSwitch from "@/hooks/use-file-switch";

type FileDrawerProps = {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  file: TelegramFile;
  onFileChange: (file: TelegramFile) => void;
} & ReturnType<typeof useFiles>;

export default function FileDrawer({
  open,
  onOpenChange,
  file,
  onFileChange,
  hasMore,
  handleLoadMore,
  isLoading,
}: FileDrawerProps) {
  const [viewing, setViewing] = useState(false);

  const { handleNavigation, direction } = useFileSwitch({
    file,
    onFileChange,
    hasMore,
    handleLoadMore,
  });

  useEffect(() => {
    if (
      viewing &&
      (file.downloadStatus !== "completed" ||
        (file.type !== "video" && file.type !== "photo"))
    ) {
      setViewing(false);
    }
  }, [file, viewing]);

  useEffect(() => {
    const handleTouchStart = (e: TouchEvent) => {
      const touch = e.touches[0];
      if (!touch) return;
      const x = touch.clientX;
      const y = touch.clientY;

      const handleTouchEnd = (e: TouchEvent) => {
        const touch = e.changedTouches[0];
        if (!touch) return;
        const dx = touch.clientX - x;
        const dy = touch.clientY - y;

        if (Math.abs(dx) > Math.abs(dy)) {
          if (dx > 0) {
            handleNavigation(-1);
          } else if (dx < 0) {
            handleNavigation(1);
          }
        }
        document.removeEventListener("touchend", handleTouchEnd);
      };
      document.addEventListener("touchend", handleTouchEnd);
    };
    document.addEventListener("touchstart", handleTouchStart);
    return () => document.removeEventListener("touchstart", handleTouchStart);
  }, [handleNavigation, file]);

  const slideVariants = {
    enter: (direction: number) => ({
      x: direction > 0 ? 500 : -500,
      opacity: 0,
    }),
    center: {
      zIndex: 1,
      x: 0,
      opacity: 1,
    },
    exit: (direction: number) => ({
      zIndex: 0,
      x: direction < 0 ? 500 : -500,
      opacity: 0,
    }),
  };

  if (!file) return null;

  return (
    <Drawer
      open={open}
      onOpenChange={(open) => {
        if (!open && viewing) {
          setViewing(false);
          return;
        }
        onOpenChange(open);
      }}
    >
      <DrawerContent
        data-fileid={file.id}
        data-prev={file.prev?.id}
        data-next={file.next?.id}
        className={cn(
          "focus:outline-none",
          viewing && "rounded-none border-none",
        )}
        aria-describedby={undefined}
      >
        <VisuallyHidden>
          <DrawerTitle>File Details</DrawerTitle>
        </VisuallyHidden>
        {isLoading && (
          <div className="fixed inset-0 z-[100] flex items-center justify-center">
            <LoaderPinwheel
              className="h-8 w-8 animate-spin"
              style={{ strokeWidth: "0.8px" }}
            />
          </div>
        )}
        <AnimatePresence initial={false} custom={direction} mode="popLayout">
          <motion.div
            key={file.id}
            custom={direction}
            variants={slideVariants}
            initial="enter"
            animate="center"
            exit="exit"
            transition={{
              x: { type: "spring", stiffness: 300, damping: 30 },
              opacity: { duration: 0.2 },
            }}
            style={{
              maxWidth: "100vw",
              maxHeight: "100vh",
            }}
          >
            {viewing ? (
              <div className="relative flex min-h-screen items-center justify-center">
                <div className="absolute left-2 top-2 z-10">
                  <Button
                    onClick={() => setViewing(false)}
                    variant="ghost"
                    size="icon"
                  >
                    <Minimize2
                      className={cn(
                        "h-8 w-8",
                        file.type === "video" &&
                          file.downloadStatus === "completed" &&
                          "text-white",
                      )}
                    />
                  </Button>
                </div>
                {file.type === "video" &&
                file.downloadStatus === "completed" ? (
                  <VideoPreview file={file} />
                ) : (
                  <PhotoPreview file={file} className="h-full" />
                )}
              </div>
            ) : (
              <FileInfo onView={() => setViewing(true)} file={file} />
            )}
          </motion.div>
        </AnimatePresence>
      </DrawerContent>
    </Drawer>
  );
}
