import { Page } from "../../components/layout/Page";
import {
  SkillBundleBoard,
  UploadBundleButton,
  useSkillBundleUpload,
} from "../../components/skills/SkillBundleBoard";

/** CR-147 FE-039: 모든 테넌트가 같이 쓰는 스킬 묶음 */
export default function PlatformSkillBundles() {
  const upload = useSkillBundleUpload("/platform/skill-bundles");
  return (
    <Page actions={<UploadBundleButton upload={upload} />}>
      <SkillBundleBoard base="/platform/skill-bundles" upload={upload} />
    </Page>
  );
}
