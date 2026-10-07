# Keyword index

`build_keyword_index.py` builds `app/src/main/assets/keyword_index.bin`, which the on-device keyword
tagger ranks against a SigLIP 2 image embedding.

```bash
pip install -r requirements.txt
python build_keyword_index.py            # full build, about 10 minutes on 16 cores
python build_keyword_index.py --export   # rewrite the .bin from the cached index.npz
```

It picks common English nouns and adjectives plus short Open Images phrases, embeds them with the
SigLIP 2 text tower (open_clip `ViT-B-16-SigLIP2`), and records each term's score spread over 300
random photos from picsum.photos. The app ranks terms by how unusual their score is for the photo
at hand, so words that match almost any photo do not crowd out the specific ones.

The text tower must match the image model the app downloads, `litert-community/SigLIP2-base-patch16-224`.
