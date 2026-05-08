# Local .zshrc for Mandy development
# 1. Source the user's global configuration
[[ -f ~/.zshrc ]] && source ~/.zshrc

# 2. Source the Mandy integration automatically
# This enables the 'mandy' command and the p10k prompt indicator
[[ -f ./.mandyrc ]] && source ./.mandyrc
