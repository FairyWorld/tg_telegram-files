import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import React, { useMemo, useState } from "react";
import useSWRMutation from "swr/mutation";
import { POST } from "@/lib/api";
import { useDebounce } from "use-debounce";
import { useToast } from "@/hooks/use-toast";
import { AutoDownloadButton } from "@/components/auto-download-button";
import { useTelegramChat } from "@/hooks/use-telegram-chat";
import { useTelegramAccount } from "@/hooks/use-telegram-account";
import {
  Accordion,
  AccordionContent,
  AccordionItem,
  AccordionTrigger,
} from "./ui/accordion";
import { Label } from "@/components/ui/label";
import { Input } from "@/components/ui/input";
import {
  type AutoDownloadRule,
  DuplicationPolicies,
  type DuplicationPolicy,
  type FileType,
  TransferPolices,
  type TransferPolicy,
  type TransferRule,
} from "@/lib/types";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Badge } from "./ui/badge";
import { Check, ChevronsUpDown, X } from "lucide-react";
import { Switch } from "@/components/ui/switch";
import { motion } from "framer-motion";
import { Popover, PopoverContent, PopoverTrigger } from "./ui/popover";
import { HoverCard, HoverCardContent, HoverCardTrigger } from "./ui/hover-card";
import { Command, CommandGroup, CommandItem, CommandList } from "./ui/command";
import { cn } from "@/lib/utils";
import { useMutationObserver } from "@/hooks/use-mutation-observer";

export default function AutoDownloadDialog() {
  const { accountId } = useTelegramAccount();
  const { isLoading, chat, reload } = useTelegramChat();
  const { toast } = useToast();
  const [open, setOpen] = useState(false);
  const [rule, setRule] = useState<AutoDownloadRule>({
    query: "",
    fileTypes: [],
  });
  const { trigger: triggerAuto, isMutating: isAutoMutating } = useSWRMutation(
    !accountId || !chat
      ? undefined
      : `/file/auto-download?telegramId=${accountId}&chatId=${chat?.id}`,
    (key, { arg }: { arg: { rule: AutoDownloadRule } }) => {
      return POST(key, arg);
    },
    {
      onSuccess: () => {
        toast({
          title: chat?.autoEnabled
            ? "Auto download disabled for this chat!"
            : "Auto download enabled for this chat!",
        });
        void reload();
        setTimeout(() => {
          setOpen(false);
        }, 1000);
      },
    },
  );

  const [debounceIsAutoMutating] = useDebounce(isAutoMutating, 500, {
    leading: true,
  });

  if (isLoading) {
    return (
      <div className="h-8 w-32 animate-pulse bg-gray-200 dark:bg-gray-700"></div>
    );
  }

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger
        asChild
        onClick={(e) => {
          e.stopPropagation();
          setOpen(!open);
        }}
      >
        {chat && <AutoDownloadButton autoEnabled={chat.autoEnabled} />}
      </DialogTrigger>
      <DialogContent
        aria-describedby={undefined}
        onPointerDownOutside={() => setOpen(false)}
        onClick={(e) => e.stopPropagation()}
        className="h-full w-full overflow-auto md:h-auto md:max-h-[85%] md:w-auto"
      >
        <DialogHeader>
          <DialogTitle>
            {chat?.autoEnabled
              ? "Disable Auto Download"
              : "Enable auto download for this chat?"}
          </DialogTitle>
        </DialogHeader>
        <DialogDescription></DialogDescription>
        {chat?.autoEnabled ? (
          <div className="space-y-4">
            {chat.autoRule && (
              <div className="space-y-4">
                <div className="flex items-center">
                  <Label className="text-sm font-semibold text-gray-900 dark:text-gray-300">
                    Current Rule
                  </Label>
                  <Badge
                    variant="outline"
                    className="ml-2 border-none bg-green-500 px-2 py-0.5 text-xs text-white dark:bg-green-800 dark:text-green-200"
                  >
                    Active
                  </Badge>
                </div>

                <div className="space-y-3">
                  {/* Filter Keyword Section */}
                  <div className="rounded-lg bg-gray-50 p-3 dark:border-gray-700 dark:bg-gray-800">
                    <div className="flex flex-col space-y-1">
                      <span className="text-xs font-medium text-gray-500">
                        Filter Keyword
                      </span>
                      <span className="text-sm text-gray-500 dark:text-gray-300">
                        {chat.autoRule.query || "No keyword specified"}
                      </span>
                    </div>
                  </div>

                  <div className="rounded-lg bg-gray-50 p-3 dark:border-gray-700 dark:bg-gray-800">
                    <span className="text-xs font-medium text-gray-500">
                      File Types
                    </span>
                    <div className="mt-2 flex flex-wrap gap-2">
                      {chat.autoRule.fileTypes.length > 0 ? (
                        chat.autoRule.fileTypes.map((type) => (
                          <Badge
                            key={type}
                            variant="secondary"
                            className="flex items-center gap-1 border-gray-200 bg-white px-3 py-1 capitalize text-gray-700 dark:border-gray-700 dark:bg-gray-800 dark:text-gray-300"
                          >
                            {type}
                          </Badge>
                        ))
                      ) : (
                        <span className="text-sm text-gray-500 dark:text-gray-300">
                          No file types selected
                        </span>
                      )}
                    </div>
                  </div>

                  {/* Transfer Rule Section */}
                  {chat.autoRule.transferRule && (
                    <div className="rounded-lg bg-gray-50 p-3 dark:border-gray-700 dark:bg-gray-800">
                      <div className="flex flex-col space-y-2">
                        <span className="text-xs font-medium text-gray-500">
                          Transfer Rule
                        </span>
                        <div className="mt-2 flex flex-col space-y-2">
                          <div className="flex flex-col space-y-1">
                            <span className="text-xs text-gray-500 dark:text-gray-300">
                              Destination Folder
                            </span>
                            <span className="rounded border border-gray-200 px-1.5 py-0.5 text-sm dark:border-gray-700 dark:bg-gray-800">
                              {chat.autoRule.transferRule.destination}
                            </span>
                          </div>
                          <div className="flex items-center justify-between">
                            <span className="text-xs text-gray-500 dark:text-gray-300">
                              Transfer Policy
                            </span>
                            <Badge variant="outline" className="font-normal">
                              {chat.autoRule.transferRule.transferPolicy}
                            </Badge>
                          </div>
                          <div className="flex items-center justify-between">
                            <span className="text-xs text-gray-500 dark:text-gray-300">
                              Duplication Policy
                            </span>
                            <Badge variant="outline" className="font-normal">
                              {chat.autoRule.transferRule.duplicationPolicy}
                            </Badge>
                          </div>
                          <div className="flex items-center justify-between">
                            <span className="text-xs text-gray-500 dark:text-gray-300">
                              Transfer History
                            </span>
                            <Badge>
                              {chat.autoRule.transferRule.transferHistory
                                ? "Enabled"
                                : "Disabled"}
                            </Badge>
                          </div>
                        </div>
                      </div>
                    </div>
                  )}
                </div>
              </div>
            )}
            <div className="space-y-4 rounded-md border border-gray-200 bg-gray-50 p-4 dark:border-gray-700 dark:bg-gray-800">
              <div className="flex items-start">
                <span className="mr-3 mt-1.5 h-3 w-2 flex-shrink-0 rounded-full bg-yellow-400"></span>
                <p className="text-sm leading-6 text-gray-700 dark:text-gray-300">
                  This will disable auto download for this chat. You can always
                  enable it later.
                </p>
              </div>
              <div className="flex items-start">
                <span className="mr-3 mt-1.5 h-3 w-2 flex-shrink-0 rounded-full bg-yellow-400"></span>
                <p className="text-sm leading-6 text-gray-700 dark:text-gray-300">
                  Files that are being downloaded will be paused and you can
                  enable automatic downloading again later.
                </p>
              </div>
            </div>
          </div>
        ) : (
          <div className="space-y-4">
            <div className="space-y-4 rounded-md border border-gray-200 bg-gray-50 p-4 dark:border-gray-700 dark:bg-gray-800">
              <div className="flex items-start">
                <span className="mr-3 mt-1.5 h-3 w-2 flex-shrink-0 rounded-full bg-cyan-400"></span>
                <p className="text-sm leading-6 text-gray-700 dark:text-gray-300">
                  This will enable auto download for this chat. Files will be
                  downloaded automatically.
                </p>
              </div>
              <div className="flex items-start">
                <span className="mr-3 mt-1.5 h-3 w-2 flex-shrink-0 rounded-full bg-cyan-400"></span>
                <p className="text-sm leading-6 text-gray-700 dark:text-gray-300">
                  Files in historical messages will be downloaded first, and
                  then files in new messages will be downloaded automatically.
                </p>
              </div>
              <div className="flex items-start">
                <span className="mr-3 mt-1.5 h-3 w-2 flex-shrink-0 rounded-full bg-cyan-400"></span>
                <p className="text-sm leading-6 text-gray-700 dark:text-gray-300">
                  Download Order:
                  <span className="ml-1 rounded bg-blue-100 px-2 text-blue-700 dark:bg-blue-800 dark:text-blue-200">
                    {"Photo -> Video -> Audio -> File"}
                  </span>
                </p>
              </div>
            </div>
            <AutoDownloadRule value={rule} onChange={setRule} />
          </div>
        )}
        <DialogFooter className="gap-2">
          <Button
            variant="outline"
            onClick={() => setOpen(false)}
            disabled={debounceIsAutoMutating}
          >
            Cancel
          </Button>
          <Button
            onClick={() => {
              const folderPathRegex = /^([\/\\])?([^<>:"|?*]+([\/\\])?)+$/;
              if (
                rule.transferRule &&
                !folderPathRegex.test(rule.transferRule.destination)
              ) {
                toast({
                  title: "Invalid destination folder",
                  description: "Please enter a valid destination folder path",
                });
                return;
              }
              void triggerAuto({ rule });
            }}
            variant={chat?.autoEnabled ? "destructive" : "default"}
            disabled={debounceIsAutoMutating}
          >
            {debounceIsAutoMutating
              ? "Submitting..."
              : chat?.autoEnabled
                ? "Disable"
                : "Enable"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

interface AutoDownloadRuleProps {
  value: AutoDownloadRule;
  onChange: (value: AutoDownloadRule) => void;
}

function AutoDownloadRule({ value, onChange }: AutoDownloadRuleProps) {
  const [autoTransfer, setAutoTransfer] = useState(false);

  const handleQueryChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    onChange({
      ...value,
      query: e.target.value,
    });
  };

  const handleFileTypeSelect = (type: string) => {
    if (value.fileTypes.includes(type as Exclude<FileType, "media">)) {
      return;
    }

    onChange({
      ...value,
      fileTypes: [...value.fileTypes, type as Exclude<FileType, "media">],
    });
  };

  const removeFileType = (typeToRemove: string) => {
    onChange({
      ...value,
      fileTypes: value.fileTypes.filter((type) => type !== typeToRemove),
    });
  };

  const handleTransferRuleChange = (changes: Partial<TransferRule>) => {
    if (!value.transferRule) {
      return;
    }

    onChange({
      ...value,
      transferRule: {
        ...value.transferRule,
        ...changes,
      },
    });
  };

  return (
    <Accordion type="single" collapsible>
      <AccordionItem value="advanced">
        <AccordionTrigger className="hover:no-underline">
          Advanced
        </AccordionTrigger>
        <AccordionContent>
          <div className="flex flex-col space-y-4 rounded-md border p-4 shadow">
            <div className="flex flex-col space-y-2">
              <Label htmlFor="filter-keyword">Filter Keyword</Label>
              <Input
                id="filter-keyword"
                type="text"
                className="w-full"
                placeholder="Enter a keyword to filter files"
                value={value.query}
                onChange={handleQueryChange}
              />
            </div>

            <div className="flex flex-col space-y-2">
              <Label htmlFor="fileTypes">Filter File Types</Label>
              <Select onValueChange={handleFileTypeSelect}>
                <SelectTrigger id="fileTypes">
                  <SelectValue placeholder="Select File Types" />
                </SelectTrigger>
                <SelectContent>
                  {["photo", "video", "audio", "file"].map((type) => (
                    <SelectItem key={type} value={type}>
                      {type}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>

              <div className="mt-2 flex flex-wrap gap-2">
                {value.fileTypes.map((type) => (
                  <Badge
                    key={type}
                    className="flex items-center gap-1 px-2 py-1"
                    variant="secondary"
                  >
                    {type}
                    <X
                      className="h-3 w-3 cursor-pointer"
                      onClick={() => removeFileType(type)}
                    />
                  </Badge>
                ))}
              </div>
            </div>
          </div>

          <div className="mt-4 flex flex-col rounded-md border p-4 shadow">
            <div className="flex h-full items-start justify-between">
              <Label htmlFor="auto-transfer" className="contents">
                Enable auto transfer files
              </Label>
              <Switch
                id="auto-transfer"
                checked={autoTransfer}
                onCheckedChange={(checked) => {
                  if (checked) {
                    onChange({
                      ...value,
                      transferRule: {
                        destination: "",
                        transferPolicy: "GROUP_BY_CHAT",
                        duplicationPolicy: "OVERWRITE",
                        transferHistory: false,
                      },
                    });
                  } else {
                    onChange({
                      ...value,
                      transferRule: undefined,
                    });
                  }
                  setAutoTransfer(checked);
                }}
              />
            </div>

            <motion.div
              className="flex flex-col space-y-4 px-1"
              initial="collapsed"
              animate={autoTransfer ? "expanded" : "collapsed"}
              variants={{
                collapsed: { opacity: 0, height: 0, overflow: "hidden" },
                expanded: { opacity: 1, height: "auto", marginTop: "1rem" },
              }}
              transition={{ duration: 0.2 }}
            >
              <div className="flex flex-col space-y-2">
                <Label htmlFor="destination">
                  Destination folder for auto transfer
                </Label>
                <Input
                  id="destination"
                  type="text"
                  className="w-full"
                  placeholder="Enter a destination folder"
                  value={value.transferRule?.destination ?? ""}
                  onChange={(e) => {
                    handleTransferRuleChange({ destination: e.target.value });
                  }}
                />
              </div>

              <div className="flex flex-col space-y-2">
                <Label htmlFor="transfer-policy">Transfer Policy</Label>
                <PolicySelect
                  policyType="transfer"
                  value={value.transferRule?.transferPolicy}
                  onChange={(policy) =>
                    handleTransferRuleChange({
                      transferPolicy: policy as TransferPolicy,
                    })
                  }
                />
              </div>

              <div className="flex flex-col space-y-2">
                <Label htmlFor="duplication-policy">Duplication Policy</Label>
                <PolicySelect
                  policyType="duplication"
                  value={value.transferRule?.duplicationPolicy}
                  onChange={(policy) =>
                    handleTransferRuleChange({
                      duplicationPolicy: policy as DuplicationPolicy,
                    })
                  }
                />
              </div>

              <div className="rounded-md border p-4">
                <div className="flex items-center justify-between">
                  <Label htmlFor="transfer-history">Transfer History</Label>
                  <Switch
                    id="transfer-history"
                    checked={value.transferRule?.transferHistory}
                    onCheckedChange={(checked) =>
                      handleTransferRuleChange({ transferHistory: checked })
                    }
                  />
                </div>
                <p className="mt-2 text-xs text-muted-foreground">
                  Transfer files that are already downloaded to the specified
                  location.
                </p>
              </div>
            </motion.div>
          </div>
        </AccordionContent>
      </AccordionItem>
    </Accordion>
  );
}

const PolicyLegends: Record<
  TransferPolicy | DuplicationPolicy,
  {
    title: string;
    description: string | React.ReactNode;
  }
> = {
  GROUP_BY_CHAT: {
    title: "Group by Chat",
    description: (
      <div className="space-y-2">
        <p className="text-sm">
          Transfer files to folders based on the chat name.
        </p>
        <p className="text-xs text-muted-foreground">Example:</p>
        <p className="inline-block rounded bg-gray-100 p-1 text-xs text-muted-foreground dark:bg-gray-800 dark:text-gray-300">
          {"/${Destination Folder}/${Telegram Id}/${Chat Id}/${file}"}
        </p>
      </div>
    ),
  },
  GROUP_BY_TYPE: {
    title: "Group by Type",
    description: (
      <div className="space-y-2">
        <p className="text-sm">
          Transfer files to folders based on the file type. <br />
          All account files will be transferred to the same folder.
        </p>
        <p className="text-xs text-muted-foreground">Example:</p>
        <p className="inline-block rounded bg-gray-100 p-1 text-xs text-muted-foreground dark:bg-gray-800 dark:text-gray-300">
          {"/${Destination Folder}/${File Type}/${file}"}
        </p>
      </div>
    ),
  },
  OVERWRITE: {
    title: "Overwrite",
    description: "This will overwrite the existing file if it already exists.",
  },
  SKIP: {
    title: "Skip",
    description: "This will skip the file if it already exists.",
  },
  RENAME: {
    title: "Rename",
    description:
      "This strategy will rename the file, add a serial number after the file name, and then move the file to the destination folder",
  },
  HASH: {
    title: "Hash",
    description:
      "Calculate the hash (md5) of the file and compare with the existing file, if the hash is the same, delete the original file and set the local path to the existing file, otherwise, move the file",
  },
};

interface PolicySelectProps {
  policyType: "transfer" | "duplication";
  value?: string;
  onChange: (value: string) => void;
}

function PolicySelect({ policyType, value, onChange }: PolicySelectProps) {
  const [open, setOpen] = useState(false);
  const polices =
    policyType === "transfer" ? TransferPolices : DuplicationPolicies;
  const [peekedPolicy, setPeekedPolicy] = useState<string>(value ?? polices[0]);

  const peekPolicyLegend = useMemo(() => {
    return PolicyLegends[peekedPolicy as TransferPolicy | DuplicationPolicy];
  }, [peekedPolicy]);

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <Button
          variant="outline"
          role="combobox"
          aria-expanded={open}
          aria-label="Select a policy"
          className="w-full justify-between"
        >
          {value ?? "Select a policy..."}
          <ChevronsUpDown className="opacity-50" />
        </Button>
      </PopoverTrigger>
      <PopoverContent align="start" className="w-[250px] p-0" modal={true}>
        <HoverCard>
          <HoverCardContent
            side="top"
            align="start"
            forceMount
            className="min-h-[150px] w-auto min-w-64 max-w-[380px]"
          >
            <div className="grid gap-2">
              <h4 className="font-medium leading-none">
                {peekPolicyLegend?.title}
              </h4>
              {typeof peekPolicyLegend?.description === "string" ? (
                <p className="text-sm text-muted-foreground">
                  {peekPolicyLegend?.description ?? ""}
                </p>
              ) : (
                peekPolicyLegend?.description
              )}
            </div>
          </HoverCardContent>
          <Command>
            <CommandList className="h-[var(--cmdk-list-height)] max-h-[400px]">
              <HoverCardTrigger />
              <CommandGroup>
                {polices.map((policy) => (
                  <PolicyItem
                    key={policy}
                    policy={policy ?? ""}
                    isSelected={value === policy}
                    onPeek={setPeekedPolicy}
                    onSelect={() => {
                      onChange(policy);
                      setOpen(false);
                    }}
                  />
                ))}
              </CommandGroup>
            </CommandList>
          </Command>
        </HoverCard>
      </PopoverContent>
    </Popover>
  );
}

interface PolicyItemProps {
  policy: string;
  isSelected: boolean;
  onSelect: () => void;
  onPeek: (policy: string) => void;
}

function PolicyItem({ policy, isSelected, onSelect, onPeek }: PolicyItemProps) {
  const ref = React.useRef<HTMLDivElement>(null);

  useMutationObserver(ref, (mutations) => {
    mutations.forEach((mutation) => {
      if (
        mutation.type === "attributes" &&
        mutation.attributeName === "aria-selected" &&
        ref.current?.getAttribute("aria-selected") === "true"
      ) {
        onPeek(policy);
      }
    });
  });

  return (
    <CommandItem
      key={policy}
      onSelect={onSelect}
      ref={ref}
      className="data-[selected=true]:bg-primary data-[selected=true]:text-primary-foreground"
    >
      {policy}
      <Check
        className={cn("ml-auto", isSelected ? "opacity-100" : "opacity-0")}
      />
    </CommandItem>
  );
}
