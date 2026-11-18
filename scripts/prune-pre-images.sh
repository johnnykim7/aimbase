#!/bin/bash
# 배포가 되돌리기용으로 붙인 <이미지>:pre-<YYYYMMDD-HHMMSS> 태그를 이미지마다 최근 남길 개수만 두고 지운다(CR-201).
# deploy.sh 가 배포가 healthy 로 끝난 뒤 서버에서 bash -s 로 돌린다. 지울 대상만 보려면 --dry-run 을 앞에 준다.
#   ssh <서버> 'bash -s -- [--dry-run] <남길 개수> <이미지> [<이미지> ...]' < scripts/prune-pre-images.sh
#
# 지우는 것은 넘겨받은 이미지의 그 형식 pre- 태그뿐이다. 사람이 붙인 다른 태그(pre-cr146-20261012 같은 것),
# 다른 이미지, 볼륨, 빌드 캐시는 보지 않는다.
# 컨테이너(멈춘 것 포함)가 쓰는 이미지의 태그는 남긴다. chaws 는 실행기 둘이 aimbase-aimbase-agent 이름 하나를 같이 써서
# 한쪽만 다시 올리면 다른 쪽은 pre- 태그만 남은 앞 이미지로 돈다.
# docker rmi 는 -f 없이 부른다. 태그가 더 있는 이미지는 태그만 떨어지고, 마지막 태그면 이미지가 지워진다.
set -u

dry=0
if [ "${1:-}" = "--dry-run" ]; then
  dry=1
  shift
fi

keep="${1:-}"
case "$keep" in
  ''|*[!0-9]*) echo "[pre 정리] 남길 개수가 숫자가 아닙니다: '$keep'"; exit 2 ;;
esac
if [ "$keep" -lt 1 ]; then
  echo "[pre 정리] 남길 개수는 1 이상이어야 합니다. 0 이면 되돌릴 이미지가 없어집니다"
  exit 2
fi
shift
if [ $# -eq 0 ]; then
  echo "[pre 정리] 이미지 이름이 없습니다"
  exit 2
fi

used=""
containers=$(docker ps -aq)
if [ -n "$containers" ]; then
  # shellcheck disable=SC2086
  used=$(docker inspect --format '{{.Image}}' $containers)
fi

for image in "$@"; do
  n=0
  for tag in $(docker image ls --format '{{.Tag}}' "$image" | grep -E '^pre-[0-9]{8}-[0-9]{6}$' | sort -r); do
    n=$((n + 1))
    if [ "$n" -le "$keep" ]; then
      echo "[pre 정리] 남김 $image:$tag"
      continue
    fi
    id=$(docker image inspect --format '{{.Id}}' "$image:$tag" 2>/dev/null) || continue
    if printf '%s\n' "$used" | grep -qxF "$id"; then
      echo "[pre 정리] 남김 $image:$tag (컨테이너가 쓰는 이미지)"
      continue
    fi
    if [ "$dry" -eq 1 ]; then
      echo "[pre 정리] 지울 대상 $image:$tag"
    elif docker rmi "$image:$tag" >/dev/null; then
      echo "[pre 정리] 지움 $image:$tag"
    else
      echo "[pre 정리] 못 지움 $image:$tag"
    fi
  done
done
exit 0
