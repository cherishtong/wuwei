import { Catalog } from '@a2ui/web_core/v0_9';
import { BASIC_FUNCTIONS } from '@a2ui/web_core/v0_9/basic_catalog';
import { WvA2uiText } from './Text';
import { WvA2uiButton } from './Button';
import { WvA2uiTextField } from './TextField';
import { WvA2uiRow } from './Row';
import { WvA2uiColumn } from './Column';
import { WvA2uiList } from './List';
import { WvA2uiImage } from './Image';
import { WvA2uiIcon } from './Icon';
import { WvA2uiVideo } from './Video';
import { WvA2uiAudioPlayer } from './AudioPlayer';
import { WvA2uiCard } from './Card';
import { WvA2uiDivider } from './Divider';
import { WvA2uiCheckBox } from './CheckBox';
import { WvA2uiSlider } from './Slider';
import { WvA2uiDateTimeInput } from './DateTimeInput';
import { WvA2uiChoicePicker } from './ChoicePicker';
import { WvA2uiTabs } from './Tabs';
import { WvA2uiModal } from './Modal';

export const wvCatalog = new Catalog(
  'https://a2ui.org/specification/v0_9/basic_catalog.json',
  [
    WvA2uiText,
    WvA2uiButton,
    WvA2uiTextField,
    WvA2uiRow,
    WvA2uiColumn,
    WvA2uiList,
    WvA2uiImage,
    WvA2uiIcon,
    WvA2uiVideo,
    WvA2uiAudioPlayer,
    WvA2uiCard,
    WvA2uiDivider,
    WvA2uiCheckBox,
    WvA2uiSlider,
    WvA2uiDateTimeInput,
    WvA2uiChoicePicker,
    WvA2uiTabs,
    WvA2uiModal,
  ] as never,
  BASIC_FUNCTIONS
);
