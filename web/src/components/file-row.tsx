import { cn } from "@/lib/utils";
import { type RowHeight } from "@/components/table-row-height-switch";
import { Checkbox } from "@/components/ui/checkbox";
import FileProgress from "@/components/file-progress";
import React, { memo, type ReactNode, useState } from "react";
import { type TelegramFile } from "@/lib/types";
import SpoiledWrapper from "@/components/spoiled-wrapper";
import {
  Tooltip,
  TooltipContent,
  TooltipProvider,
  TooltipTrigger,
} from "@/components/ui/tooltip";
import PhotoPreview from "@/components/photo-preview";
import prettyBytes from "pretty-bytes";
import FileStatus from "@/components/file-status";
import FileExtra from "@/components/file-extra";
import FileControl from "@/components/file-control";
import { FileAudioIcon, FileIcon, ImageIcon, VideoIcon } from "lucide-react";
import Image from "next/image";
import { type Column } from "./table-column-filter";
import { useFileSpeed } from "@/hooks/use-file-speed";

interface FileRowProps {
  index: number;
  className?: string;
  style?: React.CSSProperties;
  ref?: React.Ref<HTMLTableRowElement>;
  file: TelegramFile;
  checked: boolean;
  properties: {
    rowHeight: RowHeight;
    dynamicClass: {
      content: string;
      contentCell: string;
    };
    columns: Column[];
  };
  onCheckedChange: (checked: boolean) => void;
}

export default function FileRow({
  index,
  className,
  style,
  ref,
  file,
  checked,
  properties,
  onCheckedChange,
}: FileRowProps) {
  const { rowHeight, dynamicClass, columns } = properties;
  const { downloadProgress, downloadSpeed } = useFileSpeed(file.id);
  const [hovered, setHovered] = useState(false);

  const getFileIcon = (type: TelegramFile["type"]) => {
    let icon;
    switch (type) {
      case "photo":
        icon = <ImageIcon className="h-4 w-4" />;
        break;
      case "video":
        icon = <VideoIcon className="h-4 w-4" />;
        break;
      case "audio":
        icon = <FileAudioIcon className="h-4 w-4" />;
        break;
      default:
        icon = <FileIcon className="h-4 w-4" />;
    }
    return (
      <div
        className={cn(
          dynamicClass.content,
          "flex items-center justify-center rounded border",
        )}
      >
        {icon}
      </div>
    );
  };

  const columnRenders: Record<string, ReactNode> = {
    content: (
      <div className="flex items-center justify-center gap-2">
        {file.type === "photo" || file.type === "video" ? (
          file.thumbnail ? (
            <SpoiledWrapper hasSensitiveContent={file.hasSensitiveContent}>
              <TooltipProvider>
                <Tooltip>
                  <TooltipTrigger>
                    <PhotoColumnImage
                      thumbnail={file.thumbnail}
                      name={file.fileName}
                      wh={dynamicClass.content}
                    />
                  </TooltipTrigger>
                  <TooltipContent asChild>
                    <PhotoPreview
                      thumbnail={file.thumbnail}
                      name={file.fileName}
                      chatId={file.chatId}
                      messageId={file.messageId}
                    />
                  </TooltipContent>
                </Tooltip>
              </TooltipProvider>
            </SpoiledWrapper>
          ) : (
            getFileIcon(file.type)
          )
        ) : (
          getFileIcon(file.type)
        )}
      </div>
    ),
    type: (
      <div className="flex flex-col items-center">
        <span className="text-sm capitalize">{file.type}</span>
        {process.env.NODE_ENV === "development" && (
          <span className="text-xs">{file.id}</span>
        )}
      </div>
    ),
    size: <span className="text-sm">{prettyBytes(file.size)}</span>,
    status: <FileStatus file={file} />,
    extra: <FileExtra file={file} rowHeight={rowHeight} />,
    actions: (
      <FileControl
        file={file}
        downloadSpeed={downloadSpeed}
        hovered={hovered}
      />
    ),
  };

  return (
    <div
      data-index={index}
      className={cn("flex w-full flex-col border-b", className)}
      style={style}
      ref={ref}
      onMouseEnter={() => setHovered(true)}
      onMouseLeave={() => setHovered(false)}
    >
      <div className="flex w-full flex-1 items-center hover:bg-accent">
        <div className="w-[30px] text-center">
          <Checkbox
            checked={checked}
            onCheckedChange={onCheckedChange}
            disabled={file.downloadStatus !== "idle"}
          />
        </div>
        {columns.map((col) =>
          col.isVisible ? (
            <div
              key={col.id}
              className={cn(
                col.className ?? "",
                col.id === "content" ? dynamicClass.contentCell : "",
              )}
            >
              {columnRenders[col.id]}
            </div>
          ) : null,
        )}
      </div>
      <div className="w-full">
        <FileProgress file={file} downloadProgress={downloadProgress} />
      </div>
    </div>
  );
}

const PhotoColumnImage = memo(function PhotoColumnImage({
  thumbnail,
  name,
  wh,
}: {
  thumbnail: string;
  name: string;
  wh: string;
}) {
  return (
    <Image
      src={`data:image/jpeg;base64,${thumbnail}`}
      alt={name ?? "File thumbnail"}
      width={32}
      height={32}
      className={cn(wh, "rounded object-cover")}
    />
  );
});
